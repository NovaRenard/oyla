package kz.oyla.app.ui.session

/** Small testable gate that prevents a tablet from sending two taps as two attempts. */
class AnswerSubmissionGuard {
    private var acquired = false
    fun tryAcquire(): Boolean = if (acquired) false else true.also { acquired = true }
    fun release() { acquired = false }
}
