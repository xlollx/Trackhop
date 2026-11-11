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
 *  - [Invite.SERVER]: a mailbox on a member's own Trackhop server ([ServerGroupTransport]).
 */
interface GroupTransport {
    /**
     * The member files that can be read now: for a folder every file, for a channel what arrived
     * since the last read that was followed by a [publish]. Merging them again changes nothing.
     * Bounded as [FolderTransport.readAll] is; what [first] (the members known, by key) wrote is
     * read before anything else.
     */
    fun readAll(first: Set<String> = emptySet()): List<MemberFile>

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
    override fun readAll(first: Set<String>): List<MemberFile> =
        FolderTransport.readAll(folder, groupId, first.mapTo(HashSet()) { FolderTransport.fileName(groupId, it) })

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
    /** Where the key of a server mailbox is kept, the same way. */
    const val MAILBOX_KEY = "mailboxKey"
    /** This member's own sign-in to a protected ntfy server (the Authorization header's value), the same way: never in an invite. */
    const val NTFY_AUTH = "ntfyAuth"

    fun open(env: Env, group: SharedGroup): GroupTransport = when (group.transport) {
        Invite.NTFY -> NtfyTransport(env, group.id, group.folder, groupKey(env, group) ?: throw ProviderException(env.text("shared_error_key", group.name)), ntfyAuth(env, group))
        Invite.SERVER -> ServerGroupTransport(env, group.id, group.folder, mailboxKey(env, group) ?: throw ProviderException(env.text("shared_error_key", group.name)))
        else -> FolderGroupTransport(SharedFolders.open(group.folder), group.id)
    }

    /** The symmetric key of an encrypted channel: whoever holds it reads the group (it is in the invite). */
    fun groupKey(env: Env, group: SharedGroup): String? =
        env.tokens.get(SharedGroups.tokenKey(group.id))?.extra?.get(GROUP_KEY)?.takeIf { B64.decode(it)?.size == 32 }

    /** The key of the group's mailbox on a Trackhop server: whoever holds it reads the group (it is in the invite). */
    fun mailboxKey(env: Env, group: SharedGroup): String? =
        env.tokens.get(SharedGroups.tokenKey(group.id))?.extra?.get(MAILBOX_KEY)?.takeIf { ServerGroupTransport.validKey(it) }

    /** This member's sign-in to the group's ntfy server, or null when it needs none. */
    fun ntfyAuth(env: Env, group: SharedGroup): String? =
        env.tokens.get(SharedGroups.tokenKey(group.id))?.extra?.get(NTFY_AUTH)?.takeIf { NtfyServer.validAuthorization(it) }

    /** The vault entry for [auth], or none. */
    fun authEntry(auth: String?): Map<String, String> = auth?.takeIf { NtfyServer.validAuthorization(it) }?.let { mapOf(NTFY_AUTH to it) } ?: emptyMap()

    fun newGroupKey(): String = B64.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** The group's invite as this device can give it: for an encrypted channel or a server mailbox, with its key. */
    fun invite(env: Env, group: SharedGroup): Invite = when (group.transport) {
        Invite.NTFY -> group.invite().copy(key = groupKey(env, group).orEmpty(), signIn = ntfyAuth(env, group) != null)
        Invite.SERVER -> group.invite().copy(key = mailboxKey(env, group).orEmpty())
        else -> group.invite()
    }

    /** What a transport keeps on this device about a group, gone with the group. */
    fun forget(env: Env, group: SharedGroup) {
        if (group.transport == Invite.NTFY) NtfyTransport.forget(env, group.id)
        if (group.transport == Invite.SERVER) ServerGroupTransport.forget(env, group.id)
    }
}
