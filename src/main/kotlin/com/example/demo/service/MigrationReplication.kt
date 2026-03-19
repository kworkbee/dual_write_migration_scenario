package com.example.demo.service

import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.sql.Timestamp
import java.time.Instant

enum class ReplicationAction {
    UPSERT,
    DELETE
}

sealed interface ReplicationPayload {
    val action: ReplicationAction
}

data class MemberSnapshot(
    override val action: ReplicationAction,
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String,
    val bio: String
) : ReplicationPayload

data class BoardSnapshot(
    override val action: ReplicationAction,
    val id: Long,
    val name: String,
    val description: String,
    val owner: MemberSnapshot
) : ReplicationPayload

data class PostSnapshot(
    override val action: ReplicationAction,
    val id: Long,
    val title: String,
    val content: String,
    val status: String,
    val createdAt: Instant,
    val author: MemberSnapshot,
    val board: BoardSnapshot
) : ReplicationPayload

data class CommentSnapshot(
    override val action: ReplicationAction,
    val id: Long,
    val body: String,
    val createdAt: Instant,
    val author: MemberSnapshot,
    val post: PostSnapshot
) : ReplicationPayload

data class MigrationReplicationEvent(
    val payload: ReplicationPayload
)

fun Member.toSnapshot(action: ReplicationAction = ReplicationAction.UPSERT) = MemberSnapshot(
    action = action,
    id = requireNotNull(id),
    username = username,
    displayName = displayName,
    email = email,
    bio = bio
)

fun Board.toSnapshot(action: ReplicationAction = ReplicationAction.UPSERT) = BoardSnapshot(
    action = action,
    id = requireNotNull(id),
    name = name,
    description = description,
    owner = owner.toSnapshot()
)

fun Post.toSnapshot(action: ReplicationAction = ReplicationAction.UPSERT) = PostSnapshot(
    action = action,
    id = requireNotNull(id),
    title = title,
    content = content,
    status = status.name,
    createdAt = createdAt,
    author = author.toSnapshot(),
    board = board.toSnapshot()
)

fun PostComment.toSnapshot(action: ReplicationAction = ReplicationAction.UPSERT) = CommentSnapshot(
    action = action,
    id = requireNotNull(id),
    body = body,
    createdAt = createdAt,
    author = author.toSnapshot(),
    post = post.toSnapshot()
)

@Component
class MigrationReplicationListener(
    @Qualifier("newEntityManagerFactory") private val entityManagerFactory: EntityManagerFactory,
    meterRegistry: MeterRegistry,
    private val migrationMetrics: MigrationMetrics
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val successCounter = Counter.builder("migration.replication.events")
        .tag("result", "success")
        .register(meterRegistry)
    private val failureCounter = Counter.builder("migration.replication.events")
        .tag("result", "failure")
        .register(meterRegistry)

    @Async("migrationTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleAfterCommit(event: MigrationReplicationEvent) {
        try {
            val entityManager = entityManagerFactory.createEntityManager()
            entityManager.use {
                val transaction = entityManager.transaction
                transaction.begin()
                try {
                    replicate(entityManager, event.payload)
                    transaction.commit()
                } catch (ex: Exception) {
                    if (transaction.isActive) {
                        transaction.rollback()
                    }
                    throw ex
                }
            }
            successCounter.increment()
            migrationMetrics.incrementReplicationResult(
                event.payload.entityName(),
                event.payload.action.name,
                "success"
            )
        } catch (ex: Exception) {
            failureCounter.increment()
            migrationMetrics.incrementReplicationResult(
                event.payload.entityName(),
                event.payload.action.name,
                "failure"
            )
            logger.error("Failed to replicate event {}", event.payload, ex)
        }
    }

    private fun replicate(entityManager: EntityManager, payload: ReplicationPayload) {
        when (payload) {
            is MemberSnapshot -> upsertMember(entityManager, payload)
            is BoardSnapshot -> upsertBoard(entityManager, payload)
            is PostSnapshot -> upsertPost(entityManager, payload)
            is CommentSnapshot -> upsertComment(entityManager, payload)
        }
        if (payload.action == ReplicationAction.DELETE) {
            when (payload) {
                is MemberSnapshot -> entityManager.find(Member::class.java, payload.id)?.let(entityManager::remove)
                is BoardSnapshot -> entityManager.find(Board::class.java, payload.id)?.let(entityManager::remove)
                is PostSnapshot -> entityManager.find(Post::class.java, payload.id)?.let(entityManager::remove)
                is CommentSnapshot -> entityManager.find(PostComment::class.java, payload.id)?.let(entityManager::remove)
            }
        }
    }

    private fun upsertMember(entityManager: EntityManager, snapshot: MemberSnapshot): Member {
        entityManager.createNativeQuery(
            """
            insert into members (id, username, displayName, email, bio)
            values (:id, :username, :displayName, :email, :bio)
            on duplicate key update
              username = values(username),
              displayName = values(displayName),
              email = values(email),
              bio = values(bio)
            """.trimIndent()
        )
            .setParameter("id", snapshot.id)
            .setParameter("username", snapshot.username)
            .setParameter("displayName", snapshot.displayName)
            .setParameter("email", snapshot.email)
            .setParameter("bio", snapshot.bio)
            .executeUpdate()
        return entityManager.find(Member::class.java, snapshot.id)
    }

    private fun upsertBoard(entityManager: EntityManager, snapshot: BoardSnapshot): Board {
        upsertMember(entityManager, snapshot.owner)
        entityManager.createNativeQuery(
            """
            insert into boards (id, name, description, owner_id)
            values (:id, :name, :description, :ownerId)
            on duplicate key update
              name = values(name),
              description = values(description),
              owner_id = values(owner_id)
            """.trimIndent()
        )
            .setParameter("id", snapshot.id)
            .setParameter("name", snapshot.name)
            .setParameter("description", snapshot.description)
            .setParameter("ownerId", snapshot.owner.id)
            .executeUpdate()
        return entityManager.find(Board::class.java, snapshot.id)
    }

    private fun upsertPost(entityManager: EntityManager, snapshot: PostSnapshot): Post {
        upsertMember(entityManager, snapshot.author)
        upsertBoard(entityManager, snapshot.board)
        entityManager.createNativeQuery(
            """
            insert into posts (id, title, content, status, createdAt, author_id, board_id)
            values (:id, :title, :content, :status, :createdAt, :authorId, :boardId)
            on duplicate key update
              title = values(title),
              content = values(content),
              status = values(status),
              createdAt = values(createdAt),
              author_id = values(author_id),
              board_id = values(board_id)
            """.trimIndent()
        )
            .setParameter("id", snapshot.id)
            .setParameter("title", snapshot.title)
            .setParameter("content", snapshot.content)
            .setParameter("status", snapshot.status)
            .setParameter("createdAt", Timestamp.from(snapshot.createdAt))
            .setParameter("authorId", snapshot.author.id)
            .setParameter("boardId", snapshot.board.id)
            .executeUpdate()
        return entityManager.find(Post::class.java, snapshot.id)
    }

    private fun upsertComment(entityManager: EntityManager, snapshot: CommentSnapshot): PostComment {
        upsertMember(entityManager, snapshot.author)
        upsertPost(entityManager, snapshot.post)
        entityManager.createNativeQuery(
            """
            insert into post_comments (id, body, createdAt, author_id, post_id)
            values (:id, :body, :createdAt, :authorId, :postId)
            on duplicate key update
              body = values(body),
              createdAt = values(createdAt),
              author_id = values(author_id),
              post_id = values(post_id)
            """.trimIndent()
        )
            .setParameter("id", snapshot.id)
            .setParameter("body", snapshot.body)
            .setParameter("createdAt", Timestamp.from(snapshot.createdAt))
            .setParameter("authorId", snapshot.author.id)
            .setParameter("postId", snapshot.post.id)
            .executeUpdate()
        return entityManager.find(PostComment::class.java, snapshot.id)
    }
}
