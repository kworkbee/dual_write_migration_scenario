package com.example.demo.service

import com.example.demo.config.RoutingDataSourceKey
import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import com.example.demo.repository.BoardRepository
import com.example.demo.repository.MemberRepository
import com.example.demo.repository.PostCommentRepository
import com.example.demo.repository.PostRepository
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

object MigrationRouteContext {
    private val holder = ThreadLocal<RoutingDataSourceKey?>()

    fun currentTarget(): RoutingDataSourceKey? = holder.get()

    fun <T> withTarget(target: RoutingDataSourceKey, block: () -> T): T {
        val previous = holder.get()
        holder.set(target)
        return try {
            block()
        } finally {
            if (previous == null) {
                holder.remove()
            } else {
                holder.set(previous)
            }
        }
    }
}

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MigrationRoutingAspect(
    private val migrationModeResolver: MigrationModeResolver
) {

    @Around("execution(public * com.example.demo.service.CommunityService.*(..))")
    fun routeForNewMode(joinPoint: ProceedingJoinPoint): Any? {
        if (joinPoint.transactionalAnnotation() == null) {
            return joinPoint.proceed()
        }
        if (migrationModeResolver.currentMode() != MigrationMode.NEW) {
            return joinPoint.proceed()
        }
        return MigrationRouteContext.withTarget(RoutingDataSourceKey.NEW) {
            joinPoint.proceed()
        }
    }
}

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class DualWriteReplicationAspect(
    private val migrationModeResolver: MigrationModeResolver,
    private val replicationSnapshotFactory: ReplicationSnapshotFactory,
    private val eventPublisher: ApplicationEventPublisher,
    private val migrationMetrics: MigrationMetrics
) {

    @Around("execution(public * com.example.demo.service.CommunityService.*(..))")
    fun publishReplicationEvents(joinPoint: ProceedingJoinPoint): Any? {
        val transactional = joinPoint.transactionalAnnotation() ?: return joinPoint.proceed()
        if (transactional.readOnly || migrationModeResolver.currentMode() != MigrationMode.DUAL_WRITE) {
            return joinPoint.proceed()
        }

        val beforeDeletePayloads = replicationSnapshotFactory.captureDeletePayloads(joinPoint.signature.name, joinPoint.args)
        val result = joinPoint.proceed()

        val afterPayloads = when {
            beforeDeletePayloads.isNotEmpty() -> beforeDeletePayloads
            else -> replicationSnapshotFactory.captureUpsertPayload(result)
        }
        afterPayloads.forEach {
            migrationMetrics.incrementReplicationPublished(it.entityName(), it.action.name)
            eventPublisher.publishEvent(MigrationReplicationEvent(it))
        }
        return result
    }
}

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class ServiceWriteMetricsAspect(
    private val migrationModeResolver: MigrationModeResolver,
    private val migrationMetrics: MigrationMetrics
) {

    @Around("execution(public * com.example.demo.service.CommunityService.*(..))")
    fun recordWriteMetrics(joinPoint: ProceedingJoinPoint): Any? {
        val transactional = joinPoint.transactionalAnnotation() ?: return joinPoint.proceed()
        if (transactional.readOnly) {
            return joinPoint.proceed()
        }

        val mode = migrationModeResolver.currentMode()
        val operation = joinPoint.signature.name.operationName()
        val entity = joinPoint.signature.name.entityName()
        val target = when (mode) {
            MigrationMode.NEW -> "new-db"
            MigrationMode.OLD, MigrationMode.DUAL_WRITE -> "old-primary"
        }

        return try {
            val result = joinPoint.proceed()
            migrationMetrics.incrementServiceWrite(mode, entity, operation, target, "success")
            result
        } catch (ex: Exception) {
            migrationMetrics.incrementServiceWrite(mode, entity, operation, target, "failure")
            throw ex
        }
    }
}

private fun ProceedingJoinPoint.transactionalAnnotation(): Transactional? =
    (signature as? MethodSignature)?.method?.getAnnotation(Transactional::class.java)

@Component
class ReplicationSnapshotFactory(
    private val memberRepository: MemberRepository,
    private val boardRepository: BoardRepository,
    private val postRepository: PostRepository,
    private val postCommentRepository: PostCommentRepository
) {
    fun captureUpsertPayload(result: Any?): List<ReplicationPayload> =
        when (result) {
            is Member -> listOf(result.toSnapshot())
            is Board -> listOf(result.toSnapshot())
            is Post -> listOf(result.toSnapshot())
            is PostComment -> listOf(result.toSnapshot())
            else -> emptyList()
        }

    fun captureDeletePayloads(methodName: String, args: Array<Any?>): List<ReplicationPayload> {
        val id = args.firstOrNull() as? Long ?: return emptyList()
        return when (methodName) {
            "deleteMember" -> listOf(
                memberRepository.findById(id).orElseThrow().toSnapshot(ReplicationAction.DELETE)
            )
            "deleteBoard" -> listOf(
                boardRepository.findById(id).orElseThrow().toSnapshot(ReplicationAction.DELETE)
            )
            "deletePost" -> {
                val postSnapshot = postRepository.findById(id).orElseThrow().toSnapshot(ReplicationAction.DELETE)
                val nestedPostSnapshot = postSnapshot.copy(action = ReplicationAction.UPSERT)
                val commentSnapshots = postCommentRepository.findAll()
                    .filter { it.post.id == id }
                    .map {
                        CommentSnapshot(
                            action = ReplicationAction.DELETE,
                            id = requireNotNull(it.id),
                            body = it.body,
                            createdAt = it.createdAt,
                            author = it.author.toSnapshot(),
                            post = nestedPostSnapshot
                        )
                    }
                commentSnapshots + postSnapshot
            }
            "deleteComment" -> {
                val comment = postCommentRepository.findById(id).orElseThrow()
                val postSnapshot = postRepository.findById(requireNotNull(comment.post.id)).orElseThrow()
                    .toSnapshot()
                listOf(
                    CommentSnapshot(
                        action = ReplicationAction.DELETE,
                        id = requireNotNull(comment.id),
                        body = comment.body,
                        createdAt = comment.createdAt,
                        author = comment.author.toSnapshot(),
                        post = postSnapshot
                    )
                )
            }
            else -> emptyList()
        }
    }
}

internal fun ReplicationPayload.entityName(): String =
    when (this) {
        is MemberSnapshot -> "member"
        is BoardSnapshot -> "board"
        is PostSnapshot -> "post"
        is CommentSnapshot -> "comment"
    }

internal fun String.operationName(): String =
    when {
        startsWith("create") -> "create"
        startsWith("update") -> "update"
        startsWith("delete") -> "delete"
        else -> "other"
    }

internal fun String.entityName(): String =
    when {
        contains("Member") -> "member"
        contains("Board") -> "board"
        contains("Post") && !contains("Comment") -> "post"
        contains("Comment") -> "comment"
        else -> "unknown"
    }
