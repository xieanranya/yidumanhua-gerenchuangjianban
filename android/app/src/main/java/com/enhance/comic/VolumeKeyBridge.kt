package com.enhance.comic

/**
 * Lets the reader screen receive hardware volume presses. Volume keys are consumed by the
 * activity before any view sees them, so the dispatch override has to forward them here.
 */
object VolumeKeyBridge {
    /** down = 音量减；repeat = 长按产生的系统重复事件。 */
    @Volatile
    var onStep: ((down: Boolean, repeat: Boolean) -> Unit)? = null

    /** 长按是否要连续翻页，由阅读界面按设置写入。 */
    @Volatile
    var repeatEnabled: Boolean = false
}
