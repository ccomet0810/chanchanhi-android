package dev.ccomet.chanchanhi04.setup

import dev.ccomet.chanchanhi04.study.StudyState

/** Test builds always enter the study; production builds can keep ordinary guidance entry. */
object AppEntryPolicy {
    fun researchMode(enrolled:Boolean,state:StudyState,researchBuild:Boolean=dev.ccomet.chanchanhi04.BuildConfig.RESEARCH_BUILD)=researchBuild||enrolled||state.participantId!=null
    fun showStudy(enrolled:Boolean,state:StudyState,researchBuild:Boolean=dev.ccomet.chanchanhi04.BuildConfig.RESEARCH_BUILD)=researchMode(enrolled,state,researchBuild)&&
        (state.phase!=dev.ccomet.chanchanhi04.study.StudyPhase.COMPLETE||!state.completionSeen)
}
