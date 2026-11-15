package com.example.notitia.bridge

import com.example.notitia.core.CoreException
import com.example.notitia.core.InternalException
import com.example.notitia.core.askHost
import com.example.notitia.core.readMeetingSummary

// The core as Android reaches it: through the Kotlin that UniFFI generates, which loads the library on first use.
class RustCore : NotitiaCore {
    override fun meetingSummary(path: String): CoreResult<MeetingSummary> = try {
        val summary = readMeetingSummary(path)
        CoreOk(MeetingSummary(summary.id, summary.title, summary.startedAtMs))
    } catch (failure: CoreException) {
        CoreFailed(
            when (failure) {
                is CoreException.Unreadable -> CoreFailure.Unreadable
                is CoreException.NotAMeeting -> CoreFailure.NotAMeeting
                is CoreException.NewerFormat -> CoreFailure.NewerFormat
            },
        )
    } catch (_: InternalException) {
        CoreFailed(CoreFailure.Broken)
    }

    override fun ask(host: HostProbe, question: String): String =
        askHost(
            object : com.example.notitia.core.HostProbe {
                override fun answer(question: String) = host.answer(question)
            },
            question,
        )
}
