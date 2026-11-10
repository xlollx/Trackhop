package com.xlollx.songport.shared

import java.io.File
import java.security.MessageDigest

/**
 * A folder the group already shares (a Google Drive, Nextcloud, Dropbox or Syncthing folder): the
 * first way a group's members reach each other. On Android it is a folder picked with the system's
 * picker; on a computer, a plain directory. Each member writes one file and reads everyone's.
 */
interface GroupFolder {
    /** The names of the files directly in the folder. */
    fun list(): List<String>

    /** A file's bytes; null when it is gone, unreadable, or larger than [max] bytes (then not read past that). */
    fun read(name: String, max: Int): ByteArray?

    /** [name] replaced by [bytes] as a whole: atomically where the folder allows it. */
    fun write(name: String, bytes: ByteArray)
}

/** A plain directory (the server, tests, a synced folder on a computer). */
class DirFolder(private val dir: File) : GroupFolder {
    override fun list(): List<String> = dir.list()?.toList().orEmpty()

    override fun read(name: String, max: Int): ByteArray? = runCatching {
        val f = File(dir, name)
        if (!f.isFile || f.length() > max) null else f.readBytes()
    }.getOrNull()

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, ".$name.tmp")
        tmp.writeBytes(bytes)
        try {
            java.nio.file.Files.move(tmp.toPath(), File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), File(dir, name).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** How a group's folder reference (a path, or an Android content address) becomes a [GroupFolder]: the platform sets it. */
object SharedFolders {
    @Volatile var opener: (String) -> GroupFolder = { DirFolder(File(it)) }
    fun open(ref: String): GroupFolder = opener(ref)
}

/**
 * The folder's files. A member's file is `trackhop-<group>-<member>.json`; the member part is a
 * hash of their key in lowercase hex, so a folder that ignores case (Windows, macOS, most cloud
 * drives) never mixes two members up. Every file starting with the group's prefix is read, so the
 * copies a sync app makes on a conflict ("….sync-conflict-….json", "… (conflicted copy).json",
 * "… (1).json") are read too: they only hold signed events, merging them changes nothing wrong.
 * A file cut short by a copy in progress does not parse and is skipped this time.
 */
object FolderTransport {
    /** Larger than this, a file is not read at all (nothing a group writes comes near it). */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    /** At most this many events in one member's file; beyond, the oldest removals go first. */
    const val MAX_EVENTS = 20_000

    fun prefix(groupId: String) = "trackhop-$groupId-"

    fun fileName(groupId: String, member: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(member.toByteArray(Charsets.UTF_8))
        return prefix(groupId) + h.take(8).joinToString("") { "%02x".format(it) } + ".json"
    }

    /** At most this many of a group's files are read in one go (every member's file holds the whole log). */
    const val MAX_FILES = 100
    /** Past this many different events among the files read, no further file is read this time. */
    const val MAX_UNIQUE = 100_000

    /**
     * Every member file of [groupId] in [folder] that reads whole, conflict copies included; the
     * files of [first] (the members known, by [fileName]) before any other. Bounded, so a member
     * who fills the folder with files cannot exhaust the others' phones: [MAX_FILES] files, none
     * with more than [MAX_EVENTS] events, [MAX_UNIQUE] different events in all. An event found in
     * several files is one object in memory.
     */
    fun readAll(folder: GroupFolder, groupId: String, first: Set<String> = emptySet()): List<MemberFile> {
        val names = folder.list().filter { it.startsWith(prefix(groupId)) && !it.endsWith(".tmp") }
            .sortedWith(compareBy<String> { it !in first }.thenBy { it }).take(MAX_FILES)
        val one = HashMap<SharedEvent, SharedEvent>()
        val out = ArrayList<MemberFile>()
        for (name in names) {
            if (one.size > MAX_UNIQUE) break
            val f = folder.read(name, MAX_FILE_BYTES)?.let { MemberFile.parse(it.toString(Charsets.UTF_8)) } ?: continue
            if (f.card.groupId != groupId || f.events.size > MAX_EVENTS) continue
            out += f.copy(events = f.events.map { e -> one.getOrPut(e) { e } })
        }
        return out
    }

    /** Writes [file] as [member]'s, bounded: past [MAX_EVENTS], the oldest removals are left out (then the oldest additions). */
    fun write(folder: GroupFolder, groupId: String, member: String, file: MemberFile) {
        folder.write(fileName(groupId, member), MemberFile.write(bounded(file)).toByteArray(Charsets.UTF_8))
    }

    fun bounded(file: MemberFile): MemberFile {
        if (file.events.size <= MAX_EVENTS) return file
        val kept = file.events.sortedWith(compareBy<SharedEvent> { if (it.adds) 0 else 1 }.thenByDescending { it.at }.thenBy { it.id })
            .take(MAX_EVENTS)
        return file.copy(events = kept)
    }
}
