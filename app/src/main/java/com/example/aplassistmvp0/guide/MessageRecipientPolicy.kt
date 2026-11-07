package com.example.aplassistmvp0.guide

/** Chooses a conversation type, never a private contact or message recipient. */
internal object MessageRecipientPolicy {
    private val group = Regex("단체|그룹|여러|모두|전체|각각|함께|다\\s*같이|친구들|가족들|(?<!아)들\\s*(?:에게|한테)|(?:두|세|네|여러)\\s*(?:명|사람)|[2-9]\\s*명|또는|혹은|아니면|(?:와|과|랑|하고)\\s*\\S{1,12}(?:에게|한테)")
    private val recipient = Regex("[가-힣A-Za-z0-9]{1,20}(?:님|씨)?\\s*(?:한테|에게)")
    fun singleRecipient(goal: String): Boolean = !group.containsMatchIn(goal) &&
        (recipient.findAll(goal).count() == 1 || Regex("한\\s*(?:명|사람)(?:에게|한테)?").containsMatchIn(goal))
}
