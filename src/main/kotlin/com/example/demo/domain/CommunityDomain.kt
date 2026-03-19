package com.example.demo.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.ConstraintMode
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

enum class PostStatus {
    DRAFT,
    PUBLISHED
}

@Entity
@Table(name = "members")
class Member(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true, length = 50)
    var username: String = "",
    @Column(nullable = false, length = 100)
    var displayName: String = "",
    @Column(nullable = false, unique = true, length = 120)
    var email: String = "",
    @Column(nullable = false, length = 400)
    var bio: String = ""
)

@Entity
@Table(name = "boards")
class Board(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true, length = 80)
    var name: String = "",
    @Column(nullable = false, length = 400)
    var description: String = "",
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT))
    var owner: Member = Member(),
    @OneToMany(mappedBy = "board", cascade = [CascadeType.ALL], orphanRemoval = true)
    var posts: MutableList<Post> = mutableListOf()
)

@Entity
@Table(name = "posts")
class Post(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, length = 140)
    var title: String = "",
    @Column(nullable = false, length = 5000)
    var content: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PostStatus = PostStatus.DRAFT,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT))
    var author: Member = Member(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT))
    var board: Board = Board(),
    @OneToMany(mappedBy = "post", cascade = [CascadeType.ALL], orphanRemoval = true)
    var comments: MutableList<PostComment> = mutableListOf()
)

@Entity
@Table(name = "post_comments")
class PostComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, length = 1000)
    var body: String = "",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT))
    var author: Member = Member(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT))
    var post: Post = Post()
)
