package com.example.demo.api

import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import com.example.demo.domain.PostStatus
import java.time.Instant

data class MemberRequest(
    val username: String,
    val displayName: String,
    val email: String,
    val bio: String
)

data class BoardRequest(
    val name: String,
    val description: String,
    val ownerId: Long
)

data class PostRequest(
    val title: String,
    val content: String,
    val status: PostStatus,
    val authorId: Long,
    val boardId: Long
)

data class CommentRequest(
    val body: String,
    val authorId: Long,
    val postId: Long
)

data class MemberResponse(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String,
    val bio: String
)

data class BoardResponse(
    val id: Long,
    val name: String,
    val description: String,
    val ownerId: Long,
    val ownerUsername: String
)

data class PostResponse(
    val id: Long,
    val title: String,
    val content: String,
    val status: PostStatus,
    val createdAt: Instant,
    val authorId: Long,
    val authorUsername: String,
    val boardId: Long,
    val boardName: String
)

data class CommentResponse(
    val id: Long,
    val body: String,
    val createdAt: Instant,
    val authorId: Long,
    val authorUsername: String,
    val postId: Long
)

fun Member.toResponse() = MemberResponse(
    id = requireNotNull(id),
    username = username,
    displayName = displayName,
    email = email,
    bio = bio
)

fun Board.toResponse() = BoardResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    ownerId = requireNotNull(owner.id),
    ownerUsername = owner.username
)

fun Post.toResponse() = PostResponse(
    id = requireNotNull(id),
    title = title,
    content = content,
    status = status,
    createdAt = createdAt,
    authorId = requireNotNull(author.id),
    authorUsername = author.username,
    boardId = requireNotNull(board.id),
    boardName = board.name
)

fun PostComment.toResponse() = CommentResponse(
    id = requireNotNull(id),
    body = body,
    createdAt = createdAt,
    authorId = requireNotNull(author.id),
    authorUsername = author.username,
    postId = requireNotNull(post.id)
)
