package com.example.demo.repository

import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRepository : JpaRepository<Member, Long> {
    fun existsByUsername(username: String): Boolean
}

interface BoardRepository : JpaRepository<Board, Long> {
    fun existsByOwnerId(ownerId: Long): Boolean

    @EntityGraph(attributePaths = ["owner"])
    override fun findAll(): List<Board>

    @EntityGraph(attributePaths = ["owner"])
    override fun findById(id: Long): java.util.Optional<Board>
}

interface PostRepository : JpaRepository<Post, Long> {
    fun existsByAuthorId(authorId: Long): Boolean
    fun existsByBoardId(boardId: Long): Boolean

    @EntityGraph(attributePaths = ["author", "board"])
    override fun findAll(): List<Post>

    @EntityGraph(attributePaths = ["author", "board"])
    override fun findById(id: Long): java.util.Optional<Post>
}

interface PostCommentRepository : JpaRepository<PostComment, Long> {
    fun existsByAuthorId(authorId: Long): Boolean
    fun existsByPostId(postId: Long): Boolean
    fun deleteByPostId(postId: Long): Long

    @EntityGraph(attributePaths = ["author", "post"])
    override fun findAll(): List<PostComment>

    @EntityGraph(attributePaths = ["author", "post"])
    override fun findById(id: Long): java.util.Optional<PostComment>
}
