package com.example.demo.service

import com.example.demo.api.BoardRequest
import com.example.demo.api.CommentRequest
import com.example.demo.api.MemberRequest
import com.example.demo.api.PostRequest
import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import com.example.demo.repository.BoardRepository
import com.example.demo.repository.MemberRepository
import com.example.demo.repository.PostCommentRepository
import com.example.demo.repository.PostRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommunityService(
    private val memberRepository: MemberRepository,
    private val boardRepository: BoardRepository,
    private val postRepository: PostRepository,
    private val postCommentRepository: PostCommentRepository
) {

    @Transactional(readOnly = true)
    fun listMembers(): List<Member> = memberRepository.findAll().sortedBy { it.id }

    @Transactional(readOnly = true)
    fun getMember(id: Long): Member = memberRepository.findById(id).orElseThrow { notFound("Member", id) }

    @Transactional
    fun createMember(request: MemberRequest): Member {
        require(!memberRepository.existsByUsername(request.username)) { "username already exists: ${request.username}" }
        return memberRepository.save(
            Member(
                username = request.username,
                displayName = request.displayName,
                email = request.email,
                bio = request.bio
            )
        )
    }

    @Transactional
    fun updateMember(id: Long, request: MemberRequest): Member {
        val member = memberRepository.findById(id).orElseThrow { notFound("Member", id) }
        member.username = request.username
        member.displayName = request.displayName
        member.email = request.email
        member.bio = request.bio
        return member
    }

    @Transactional
    fun deleteMember(id: Long) {
        if (boardRepository.existsByOwnerId(id) || postRepository.existsByAuthorId(id) || postCommentRepository.existsByAuthorId(id)) {
            throw IllegalStateException("Delete comments/posts/boards owned by member=$id first")
        }
        val member = memberRepository.findById(id).orElseThrow { notFound("Member", id) }
        memberRepository.delete(member)
    }

    @Transactional(readOnly = true)
    fun listBoards(): List<Board> = boardRepository.findAll().sortedBy { it.id }

    @Transactional(readOnly = true)
    fun getBoard(id: Long): Board = boardRepository.findById(id).orElseThrow { notFound("Board", id) }

    @Transactional
    fun createBoard(request: BoardRequest): Board {
        val owner = memberRepository.findById(request.ownerId).orElseThrow { notFound("Member", request.ownerId) }
        return boardRepository.save(Board(name = request.name, description = request.description, owner = owner))
    }

    @Transactional
    fun updateBoard(id: Long, request: BoardRequest): Board {
        val board = boardRepository.findById(id).orElseThrow { notFound("Board", id) }
        board.name = request.name
        board.description = request.description
        board.owner = memberRepository.findById(request.ownerId).orElseThrow { notFound("Member", request.ownerId) }
        return board
    }

    @Transactional
    fun deleteBoard(id: Long) {
        if (postRepository.existsByBoardId(id)) {
            throw IllegalStateException("Delete posts in board=$id first")
        }
        val board = boardRepository.findById(id).orElseThrow { notFound("Board", id) }
        boardRepository.delete(board)
    }

    @Transactional(readOnly = true)
    fun listPosts(): List<Post> = postRepository.findAll().sortedBy { it.id }

    @Transactional(readOnly = true)
    fun getPost(id: Long): Post = postRepository.findById(id).orElseThrow { notFound("Post", id) }

    @Transactional
    fun createPost(request: PostRequest): Post {
        val author = memberRepository.findById(request.authorId).orElseThrow { notFound("Member", request.authorId) }
        val board = boardRepository.findById(request.boardId).orElseThrow { notFound("Board", request.boardId) }
        return postRepository.save(
            Post(
                title = request.title,
                content = request.content,
                status = request.status,
                author = author,
                board = board
            )
        )
    }

    @Transactional
    fun updatePost(id: Long, request: PostRequest): Post {
        val post = postRepository.findById(id).orElseThrow { notFound("Post", id) }
        post.title = request.title
        post.content = request.content
        post.status = request.status
        post.author = memberRepository.findById(request.authorId).orElseThrow { notFound("Member", request.authorId) }
        post.board = boardRepository.findById(request.boardId).orElseThrow { notFound("Board", request.boardId) }
        return post
    }

    @Transactional
    fun deletePost(id: Long) {
        postCommentRepository.deleteByPostId(id)
        val post = postRepository.findById(id).orElseThrow { notFound("Post", id) }
        postRepository.delete(post)
    }

    @Transactional(readOnly = true)
    fun listComments(): List<PostComment> = postCommentRepository.findAll().sortedBy { it.id }

    @Transactional(readOnly = true)
    fun getComment(id: Long): PostComment = postCommentRepository.findById(id).orElseThrow { notFound("Comment", id) }

    @Transactional
    fun createComment(request: CommentRequest): PostComment {
        val author = memberRepository.findById(request.authorId).orElseThrow { notFound("Member", request.authorId) }
        val post = postRepository.findById(request.postId).orElseThrow { notFound("Post", request.postId) }
        return postCommentRepository.save(PostComment(body = request.body, author = author, post = post))
    }

    @Transactional
    fun updateComment(id: Long, request: CommentRequest): PostComment {
        val comment = postCommentRepository.findById(id).orElseThrow { notFound("Comment", id) }
        comment.body = request.body
        comment.author = memberRepository.findById(request.authorId).orElseThrow { notFound("Member", request.authorId) }
        comment.post = postRepository.findById(request.postId).orElseThrow { notFound("Post", request.postId) }
        return comment
    }

    @Transactional
    fun deleteComment(id: Long) {
        val comment = postCommentRepository.findById(id).orElseThrow { notFound("Comment", id) }
        postCommentRepository.delete(comment)
    }

    private fun notFound(type: String, id: Long) = EntityNotFoundException("$type not found: $id")
}
