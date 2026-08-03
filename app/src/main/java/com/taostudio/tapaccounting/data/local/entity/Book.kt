package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Stable identity for a book. [name] is mutable display data. */
@Entity(
    tableName = "books",
    indices = [Index(value = ["name"], unique = true)]
)
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
