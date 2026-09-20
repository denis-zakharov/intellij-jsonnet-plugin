package com.dz.intellijjsonnet.engine

import com.dz.intellijjsonnet.shaded.scala.util.Right
import com.dz.intellijjsonnet.shaded.sjsonnet.Interpreter
import com.dz.intellijjsonnet.shaded.sjsonnet.Path
import com.dz.intellijjsonnet.shaded.sjsonnet.Val

/** One step in the path from the evaluation root down to a value in the output. */
sealed class PathSegment {
    data class Key(val name: String) : PathSegment()
    data class Index(val index: Int) : PathSegment()
}

/** A character [offset] into the text of [path] (the exact string sjsonnet parsed). */
data class SourceLocation(val path: Path, val offset: Int)

/**
 * Preview's output→source click-jump (TODO.md item 4): given the path to a value in the rendered
 * output (`spec.containers[0].name`), finds the source position that *produced* that value, using
 * sjsonnet's own evaluation rather than a PSI approximation — so it follows `local`s, `+`
 * composition, `super`, function calls and imports, and can land in a different file than the one
 * that was evaluated (`SourceLocation.path`).
 *
 * "Produced" is literal: the position is `Val.pos` of the evaluated value, i.e. the literal or
 * operator expression that created it (`replicas: 3` → the `3`; `name: 'a' + n` → the `+`;
 * `x: v` with `local v = 5` → that `5`). It is deliberately *not* the field's key: sjsonnet 0.7.4
 * folds constant-field objects into `ConstMember(value)`s that keep no key position at all
 * (verified by reflection probe — only non-constant objects retain `_sourceMemberList` with real
 * `Field` positions), so mixing key positions in "when available" would make the landing spot
 * unpredictable. Nested objects resolve to their `{`; the path stops at the deepest value reachable
 * if it leaves what evaluation can follow (comprehension-computed keys are ordinary keys here, so
 * they work too).
 *
 * Re-evaluates the root lazily on first use (cheap: the parse cache is warm and object fields are
 * lazy, so only the fields along the requested path are forced) rather than retaining the [Val]
 * tree from the render pass, which went through the string-error-formatting entry point that
 * doesn't expose it.
 */
class SourceLocator internal constructor(
    private val interpreter: Interpreter,
    private val source: String,
    private val path: Path,
) {
    private val root: Val? by lazy {
        (interpreter.evaluate(source, path) as? Right<*, *>)?.value() as? Val
    }

    fun locate(segments: List<PathSegment>): SourceLocation? {
        var current: Val = root ?: return null
        val evaluator = interpreter.evaluator()
        try {
            for (segment in segments) {
                val next: Val = when (segment) {
                    is PathSegment.Key -> {
                        val obj = current as? Val.Obj ?: break
                        if (!obj.containsKey(segment.name)) break
                        obj.value(segment.name, obj.pos(), obj.`value$default$3`(), evaluator)
                    }
                    is PathSegment.Index -> {
                        val arr = current as? Val.Arr ?: break
                        if (segment.index < 0 || segment.index >= arr.length()) break
                        arr.value(segment.index)
                    }
                }
                current = next
            }
        } catch (e: Exception) {
            // Forcing a field along the path failed (the render pass would have reported it);
            // jump to the deepest value reached so far.
        }
        val pos = current.pos()
        return SourceLocation(pos.currentFile(), pos.offset())
    }
}
