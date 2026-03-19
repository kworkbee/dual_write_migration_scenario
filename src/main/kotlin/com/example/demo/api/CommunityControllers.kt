package com.example.demo.api

import com.example.demo.service.CommunityService
import com.example.demo.service.MigrationModeResolver
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.transaction.annotation.Transactional

@RestController
@RequestMapping("/api/members")
class MemberController(private val service: CommunityService) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list() = service.listMembers().map { it.toResponse() }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: Long) = service.getMember(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: MemberRequest) = service.createMember(request).toResponse()

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: MemberRequest) = service.updateMember(id, request).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.deleteMember(id)
}

@RestController
@RequestMapping("/api/boards")
class BoardController(private val service: CommunityService) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list() = service.listBoards().map { it.toResponse() }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: Long) = service.getBoard(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: BoardRequest) = service.createBoard(request).toResponse()

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: BoardRequest) = service.updateBoard(id, request).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.deleteBoard(id)
}

@RestController
@RequestMapping("/api/posts")
class PostController(private val service: CommunityService) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list() = service.listPosts().map { it.toResponse() }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: Long) = service.getPost(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: PostRequest) = service.createPost(request).toResponse()

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: PostRequest) = service.updatePost(id, request).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.deletePost(id)
}

@RestController
@RequestMapping("/api/comments")
class CommentController(private val service: CommunityService) {
    @GetMapping
    @Transactional(readOnly = true)
    fun list() = service.listComments().map { it.toResponse() }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun get(@PathVariable id: Long) = service.getComment(id).toResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CommentRequest) = service.createComment(request).toResponse()

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: CommentRequest) = service.updateComment(id, request).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.deleteComment(id)
}

@RestController
@RequestMapping("/api/migration")
class MigrationController(private val migrationModeResolver: MigrationModeResolver) {
    @GetMapping("/mode")
    fun mode(): Map<String, String> = mapOf("mode" to migrationModeResolver.currentMode().name)
}
