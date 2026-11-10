package com.xlollx.songport.shared

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.platform.Env
import java.security.SecureRandom

/**
 * How a group's members reach each other. Each member publishes one [MemberFile] (their card, the
 * newest curators' list, every event they know) and reads everyone else's; what travels is only
 * signed data, so a transport carries the group without deciding who may write to it.
 *
 *  - [Invite.FOLDER]: a folder the group already shares ([FolderGroupTransport]).
 *  - [Invite.NTFY]: an end-to-end encrypted topic on an ntfy server ([NtfyTransport]).
 */
interface GroupTransport {
    /**
     * The member files that can be read now: for a folder every file, for a channel what arrived
     * since the last read that was followed by a [publish]. Merging them again changes nothing.
     */
    fun readAll(): List<MemberFile>

    /**
     * Publishes [file] as [member]'s when the transport needs it (it changed, went missing, or is
     * due again). [written] is what the previous publish answered; the answer is stored for the next.
     */
    fun publish(member: String, file: MemberFile, written: Int): Int

    /** What the member reads when it cannot be reached. */
    fun unreachable(env: Env, group: SharedGroup): String
}

/** The shared folder as a transport: one file per member, written again when it changed or went missing. */
class FolderGroupTransport(private val folder: GroupFolder, private val groupId: String) : GroupTransport {
    override fun readAll(): List<MemberFile> = FolderTransport.readAll(folder, groupId)

    override fun publish(member: String, file: MemberFile, written: Int): Int {
        val text = MemberFile.write(FolderTransport.bounded(file))
        val name = FolderTransport.fileName(groupId, member)
        val hash = text.hashCode()
        if (hash != written || name !in folder.list()) folder.write(name, text.toByteArray(Charsets.UTF_8))
        return hash
    }

    override fun unreachable(env: Env, group: SharedGroup): String = env.text("shared_error_folder", group.folderName.ifBlank { group.name })
}

/** The transport of each group, and what one needs besides the member's own key. */
object SharedTransports {
    /** Where the group key of an encrypted channel is kept, next to the member's own key in the vault. */
    const val GROUP_KEY = "groupKey"

    fun open(env: Env, group: SharedGroup): GroupTransport = when (group.transport) {
        Invite.NTFY -> NtfyTransport(env, group.id, group.folder, groupKey(env, group) ?: throw ProviderException(env.text("shared_error_key", group.name)))
        else -> FolderGroupTransport(SharedFolders.open(group.folder), group.id)
    }

    /** The symmetric key of an encrypted channel: whoever holds it reads the group (it is in the invite). */
    fun groupKey(env: Env, group: SharedGroup): String? =
        env.tokens.get(SharedGroups.tokenKey(group.id))?.extra?.get(GROUP_KEY)?.takeIf { B64.decode(it)?.size == 32 }

    fun newGroupKey(): String = B64.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** The group's invite as this device can give it: for an encrypted channel, with its key. */
    fun invite(env: Env, group: SharedGroup): Invite =
        if (group.transport == Invite.NTFY) group.invite().copy(key = groupKey(env, group).orEmpty()) else group.invite()

    /** What a transport keeps on this device about a group, gone with the group. */
    fun forget(env: Env, group: SharedGroup) {
        if (group.transport == Invite.NTFY) NtfyTransport.forget(env, group.id)
    }
}
