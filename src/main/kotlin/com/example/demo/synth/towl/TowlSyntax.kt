package com.example.demo.synth.towl

/**
 * TOWL v3 surface syntax (TOWL_SPEC.md §§2–3): lexer, AST, and a recursive-descent parser.
 *
 * The parser knows the catalog's namespace set so that `ns.op(...)` is recognized as the only
 * effectful form at parse time; everything else about legality (pure layer, argument kinds,
 * types) is the checker's job, so that one pass can report every problem.
 */

data class Pos(val line: Int, val col: Int) {
    override fun toString() = "$line:$col"
}

data class Diagnostic(
    val severity: String, // "error" | "warning"
    val phase: String, // syntax | names | catalog | types | effects | input
    val code: String,
    val pos: Pos?,
    val message: String,
    val fix: String? = null,
    val type: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "severity" to severity, "phase" to phase, "code" to code,
        "line" to pos?.line, "col" to pos?.col, "message" to message, "fix" to fix, "type" to type,
    )

    override fun toString() = "$severity[$code] ${pos ?: "-"}: $message" + (fix?.let { " (fix: $it)" } ?: "")
}

class TowlException(val diagnostics: List<Diagnostic>) :
    RuntimeException(diagnostics.joinToString("; ") { it.toString() })

// ── AST ──────────────────────────────────────────────────────────────────────

sealed interface Expr { val pos: Pos }
data class Lit(val value: Any?, override val pos: Pos) : Expr
data class Ref(val name: String, override val pos: Pos) : Expr
/** The implicit element of an Express function; root of every `.a.b` path. */
data class Implicit(override val pos: Pos) : Expr
data class RecordE(val fields: List<Pair<String, Expr>>, override val pos: Pos) : Expr
data class ListE(val items: List<Expr>, override val pos: Pos) : Expr
data class BlockE(val bindings: List<Binding>, val result: Expr, override val pos: Pos) : Expr
data class Member(val target: Expr, val name: String, val nullSafe: Boolean, override val pos: Pos) : Expr
data class MethodCall(val target: Expr, val name: String, val args: List<Arg>, override val pos: Pos, val multiline: Boolean = false,
                      val layout: Pair<Int?, Int?> = null to null) : Expr
data class OpCall(val namespace: String, val operation: String, val params: Expr, val options: Expr?, override val pos: Pos) : Expr

sealed interface Arg { val pos: Pos }
data class ExprArg(val expr: Expr, override val pos: Pos) : Arg
data class PredArg(val pred: Pred, override val pos: Pos) : Arg
data class LambdaArg(val param: String, val body: Expr, override val pos: Pos) : Arg

sealed interface Pred { val pos: Pos }
data class Cmp(val op: String, val left: Expr, val right: Expr, override val pos: Pos) : Pred
data class InP(val left: Expr, val right: Expr, override val pos: Pos) : Pred
data class AndP(val terms: List<Pred>, override val pos: Pos) : Pred
data class OrP(val terms: List<Pred>, override val pos: Pos) : Pred
data class NotP(val term: Pred, override val pos: Pos) : Pred
/** present() absent() contains(s) starts_with(s) ends_with(s) */
data class TestP(val operand: Expr, val fn: String, val arg: Expr?, override val pos: Pos) : Pred
/** operand.any(pred) / operand.all(pred) over a list-typed operand. */
data class QuantP(val operand: Expr, val all: Boolean, val inner: Pred, override val pos: Pos) : Pred

data class Binding(val name: String, val expr: Expr, val pos: Pos)
data class InputDecl(val name: String, val type: Type, val pos: Pos)
data class Program(val description: String?, val inputs: List<InputDecl>, val bindings: List<Binding>, val result: Expr)

object Stdlib {
    val EXPR = setOf("project", "flat", "flatten", "where", "compact", "distinct", "concat", "group", "single")
    val AGG = setOf("count", "sum", "min", "max", "avg", "collect", "any", "all", "top", "bottom")
    val STR = setOf("after_last", "before_first", "lower", "upper")
    val TIME = setOf("minus_days", "minus_hours", "minus_minutes", "start_of_day", "start_of_month", "date")
    val TEST = setOf("present", "absent", "empty", "contains", "starts_with", "ends_with")
    val PRED_ARG = setOf("where", "any", "all")
    val ALL = EXPR + AGG + STR + TIME
    const val FOR_FORM = "the binder is written: for x in xs  then the body: a record on the same line, or bindings and a result on indented lines"
    val REMOVED = mapOf(
        "or" to "'.or' was removed: keep the value nullable (T | Null); use ?. to reach through it and pass it to arguments as is",
        "each" to "'each' is now a for expression: $FOR_FORM",
        "map" to "'map' is now a for expression: $FOR_FORM",
    )
    const val CALL_FORM = "operation calls are written call(\"namespace\", \"operation\", { param: value }, { tolerate: [...] }); args and options may be omitted"
    val TYPE_KEYWORDS = setOf("string", "int", "number", "bool", "timestamp", "Null", "json", "list")
    val KEYWORDS = setOf("towl", "input", "in", "for", "call", "true", "false", "null") + TYPE_KEYWORDS
}

// ── lexer ────────────────────────────────────────────────────────────────────

enum class TK { IDENT, STRING, NUMBER, OP, EOF }
data class Token(val kind: TK, val text: String, val pos: Pos)

class Lexer(private val src: String) {
    private var i = 0
    private var line = 1
    private var col = 1

    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            skipTrivia()
            if (i >= src.length) { out += Token(TK.EOF, "", Pos(line, col)); return out }
            val pos = Pos(line, col)
            val c = src[i]
            when {
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) adv()
                    out += Token(TK.IDENT, src.substring(start, i), pos)
                }
                c.isDigit() || (c == '-' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val start = i
                    adv()
                    while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' ||
                            ((src[i] == '-' || src[i] == '+') && (src[i - 1] == 'e' || src[i - 1] == 'E')))) adv()
                    out += Token(TK.NUMBER, src.substring(start, i), pos)
                }
                c == '"' || c == '\'' -> out += Token(TK.STRING, string(c), pos)
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    val op = when {
                        two in setOf("?.", "=>", "==", "!=", "<=", ">=", "&&", "||") -> two
                        c in "()[]{}.,:=<>!|@" -> c.toString()
                        else -> throw TowlException(listOf(Diagnostic("error", "syntax", "syntax.badChar", pos, "unexpected character '$c'")))
                    }
                    repeat(op.length) { adv() }
                    out += Token(TK.OP, op, pos)
                }
            }
        }
    }

    private fun string(quote: Char): String {
        val pos = Pos(line, col)
        adv()
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw TowlException(listOf(Diagnostic("error", "syntax", "syntax.unterminatedString", pos, "unterminated string")))
            val c = src[i]
            if (c == quote) { adv(); return sb.toString() }
            if (c == '\\') {
                adv()
                val e = src.getOrNull(i) ?: continue
                sb.append(when (e) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'u' -> { val h = src.substring(i + 1, i + 5); repeat(4) { adv() }; h.toInt(16).toChar() }; else -> e })
                adv()
            } else { sb.append(c); adv() }
        }
    }

    private fun skipTrivia() {
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\t' -> throw TowlException(listOf(Diagnostic("error", "syntax", "syntax.tab", Pos(line, col), "tab characters are not allowed; indentation is significant and uses spaces", "indent with 2 spaces per level")))
                c == ' ' || c == '\r' || c == '\n' || c == ';' -> adv() // ';' is an ignored separator
                c == '#' || (c == '/' && i + 1 < src.length && src[i + 1] == '/') -> while (i < src.length && src[i] != '\n') adv()
                else -> return
            }
        }
    }

    private fun adv() {
        if (src[i] == '\n') { line++; col = 1 } else col++
        i++
    }
}

// ── parser ───────────────────────────────────────────────────────────────────

class Parser(src: String, private val namespaces: Set<String>) { // namespaces: only for `ns.Shape` type names
    private val t = Lexer(src).tokens()
    private var p = 0
    /** bracket depth at each token: layout is suspended inside brackets */
    private val depth: IntArray = IntArray(t.size).also { d ->
        var k = 0
        for ((i, tok) in t.withIndex()) {
            if (tok.kind == TK.OP && tok.text in setOf(")", "]", "}")) k = maxOf(0, k - 1)
            d[i] = k
            if (tok.kind == TK.OP && tok.text in setOf("(", "[", "{")) k++
        }
    }
    /** indentation (column) of each open block; null until its first line-starting item */
    private val blocks = ArrayList<Int?>()

    private fun startsLine(i: Int = p) = i == 0 || t[i].pos.line > t[i - 1].pos.line

    /** at the first token of a block item: check it against the block's indentation */
    private fun itemStart() {
        val tok = peek()
        if (tok.kind == TK.EOF || !startsLine() || depth[p] > 0 || blocks.isEmpty()) return
        val indent = blocks.last()
        if (indent == null) blocks[blocks.size - 1] = tok.pos.col
        else if (tok.pos.col != indent) err("syntax.indent", "'${tok.text}' is indented to column ${tok.pos.col} but the items of this block start at column $indent", tok.pos,
            "items of one block (bindings and the result) share one indentation; a continuation line starts with '.'")
    }

    /** `binding* expr` at the current block's indentation; the block ends at its result */
    private fun blockItems(what: String): Pair<List<Binding>, Expr> {
        val bindings = ArrayList<Binding>()
        while (peek().kind == TK.IDENT && peek(1).kind == TK.OP && peek(1).text == "=") {
            itemStart()
            val name = t[p++]
            expect("=")
            bindings += Binding(name.text, expr(), name.pos)
        }
        if (peek().kind == TK.EOF) err("syntax.noResult", "a $what ends with a result expression after its bindings")
        itemStart()
        return bindings to expr()
    }

    private fun peek(k: Int = 0) = t[minOf(p + k, t.size - 1)]
    private fun at(text: String) = peek().kind == TK.OP && peek().text == text
    private fun atIdent(text: String? = null) = peek().kind == TK.IDENT && (text == null || peek().text == text)
    private fun err(code: String, msg: String, pos: Pos = peek().pos, fix: String? = null): Nothing =
        throw TowlException(listOf(Diagnostic("error", "syntax", code, pos, msg, fix)))
    private fun expect(text: String): Token = if (at(text)) t[p++] else err("syntax.expected", "expected '$text' but found '${peek().text.ifEmpty { "end of input" }}'")
    private fun ident(): Token = if (peek().kind == TK.IDENT) t[p++] else err("syntax.expected", "expected a name but found '${peek().text.ifEmpty { "end of input" }}'")

    fun program(): Program {
        val head = ident()
        if (head.text != "towl") err("syntax.header", "a program starts with 'towl 3'", head.pos)
        val ver = peek()
        if (ver.kind != TK.NUMBER || ver.text != "3") err("syntax.version", "unsupported TOWL version '${ver.text}'; this processor implements version 3", ver.pos)
        p++
        val description = if (peek().kind == TK.STRING) t[p++].text else null
        blocks += null
        val inputs = ArrayList<InputDecl>()
        while (atIdent("input")) {
            itemStart()
            val pos = t[p++].pos
            val name = ident().text
            expect(":")
            inputs += InputDecl(name, type(), pos)
        }
        val (bindings, result) = blockItems("program")
        if (peek().kind != TK.EOF) {
            if (peek().kind == TK.IDENT && peek(1).text == "=")
                err("syntax.trailing", "binding '${peek().text}' comes after the result expression", fix = "the result is the last line of the program; move this binding above it")
            err("syntax.trailing", "unexpected '${peek().text}' after the result expression", fix = "a program is: towl 3, inputs, bindings (name = expr), then exactly one result expression")
        }
        return Program(description, inputs, bindings, result)
    }

    fun type(): Type {
        val tok = peek()
        var base: Type = when {
            tok.kind == TK.IDENT && tok.text in setOf("string", "int", "number", "bool", "timestamp", "Null", "json") -> {
                p++
                when (tok.text) { "string" -> TString; "int" -> TInt; "number" -> TNumber; "bool" -> TBool; "timestamp" -> TTimestamp; "Null" -> TNull; else -> TJson }
            }
            tok.kind == TK.IDENT && tok.text == "list" -> { p++; expect("["); val e = type(); expect("]"); TList(e) }
            at("{") -> {
                p++
                val fields = LinkedHashMap<String, Type>()
                while (!at("}")) {
                    val n = ident().text; expect(":"); fields[n] = type()
                    if (at(",")) p++ else break
                }
                expect("}")
                TRecord(fields)
            }
            tok.kind == TK.IDENT && namespaces.contains(tok.text) && peek(1).text == "." -> {
                p += 2; val shape = ident().text
                err("syntax.shapeType", "catalog shape types (${tok.text}.$shape) are not available in this catalog; spell the record type out", tok.pos)
            }
            else -> err("syntax.type", "expected a type (string, int, number, bool, timestamp, Null, json, list[T], { field: T }) but found '${tok.text}'")
        }
        if (at("|")) { p++; val n = ident(); if (n.text != "Null") err("syntax.type", "only '| Null' may follow a type", n.pos); base = Types.nullable(base) }
        return base
    }

    // expr = primary postfix*
    fun expr(): Expr {
        var e = primary()
        if (e is MethodCall && e.name == "for" && e.multiline) {
            // a laid-out body ends the expression, except for continuation lines indented between the
            // `for` line and its body: those apply to the for expression itself (`  .flatten()`)
            val (header, body) = e.layout
            while ((at(".") || at("?.")) && startsLine() && depth[p] == 0 && header != null && body != null && peek().pos.col > header && peek().pos.col < body)
                e = postfix(e)
            return e
        }
        while ((at(".") || at("?.")) && !dedentedContinuation()) e = postfix(e)
        return e
    }

    /** a `.` line indented less than the current block's items belongs to an enclosing expression */
    private fun dedentedContinuation(): Boolean {
        val indent = blocks.lastOrNull() ?: return false
        return startsLine() && depth[p] == 0 && peek().pos.col < indent
    }

    private fun postfix(target: Expr): Expr {
        val dot = t[p++]
        val nullSafe = dot.text == "?."
        val name = ident()
        if (name.text in Stdlib.REMOVED && (at("(") || at("@"))) err("syntax.removed", Stdlib.REMOVED.getValue(name.text), name.pos)
        return if (at("(") && !nullSafe && name.text in Stdlib.ALL) {
            p++
            val args = if (at(")")) emptyList() else args(name.text)
            expect(")")
            MethodCall(target, name.text, args, name.pos)
        } else if (at("(") && !nullSafe && name.text in Stdlib.TEST) {
            // test functions parse as method calls so predicate parsing can recognize them
            p++
            val args = if (at(")")) emptyList() else args(name.text)
            expect(")")
            MethodCall(target, name.text, args, name.pos)
        } else if (at("(")) {
            if (target is Ref) // the retired `namespace.operation(...)` form
                err("syntax.callForm", "'${target.name}.${name.text}(...)' is not how operations are called", name.pos,
                    "write call(\"${target.name}\", \"${name.text}\", { ... }); ${Stdlib.CALL_FORM}")
            err("syntax.unknownFunction", "'${name.text}' is not a TOWL function", name.pos,
                "functions: ${(Stdlib.ALL + Stdlib.TEST).sorted().joinToString(" ")}; an operation is call(\"namespace\", \"operation\", { ... })")
        } else Member(target, name.text, nullSafe, name.pos)
    }

    /** `for x in source` then a body: a single expression on the same line, or an indented block. */
    private fun forExpr(): Expr {
        val kw = t[p++]
        if (at("(")) err("syntax.forForm", "'for' takes no parentheses", peek().pos, Stdlib.FOR_FORM)
        val param = ident()
        if (!atIdent("in")) err("syntax.forForm", "expected 'in' after 'for ${param.text}'", peek().pos, Stdlib.FOR_FORM)
        p++
        val source = expr()
        if (at(":") && !startsLine()) p++ // the Python reflex; accepted and dropped by the canonical rendering
        val nxt = peek()
        if (nxt.kind == TK.EOF) err("syntax.forBody", "'for ${param.text} in ...' has no body", kw.pos, Stdlib.FOR_FORM)
        if (!startsLine()) {
            if (at("{") && peek(1).kind == TK.IDENT && peek(2).text == "=")
                err("syntax.forBody", "a for body with bindings is written on indented lines, not in braces", nxt.pos, Stdlib.FOR_FORM)
            return MethodCall(source, "for", listOf(LambdaArg(param.text, expr(), param.pos)), kw.pos, multiline = false)
        }
        val headerIndent = blocks.lastOrNull()
        val checked = depth[p] == 0
        if (checked && headerIndent != null && nxt.pos.col <= headerIndent)
            err("syntax.indent", "the body of 'for ${param.text}' must be indented more than the line that starts it (column $headerIndent)", nxt.pos, Stdlib.FOR_FORM)
        blocks += if (checked) nxt.pos.col else null
        val (bindings, result) = blockItems("for ${param.text} body")
        val bodyIndent = blocks.removeAt(blocks.size - 1)
        val after = peek()
        if (checked && after.kind != TK.EOF && startsLine() && depth[p] == 0 && headerIndent != null && after.pos.col > headerIndent
            && !(after.text in setOf(".", "?.") && bodyIndent != null && after.pos.col < bodyIndent))
            err("syntax.resultNotLast", "this line is indented as part of the 'for ${param.text}' body, but that body already ended with its result on line ${result.pos.line}", after.pos,
                "the result of a body is its last line: move the result below this line, or bind this value before the result")
        val body: Expr = if (bindings.isEmpty()) result else BlockE(bindings, result, nxt.pos)
        return MethodCall(source, "for", listOf(LambdaArg(param.text, body, param.pos)), kw.pos, multiline = true, layout = headerIndent to bodyIndent)
    }

    private fun args(fn: String): List<Arg> {
        val out = ArrayList<Arg>()
        while (true) {
            val pos = peek().pos
            if (peek().kind == TK.IDENT && peek(1).text == "=>") err("syntax.lambda", "TOWL has no lambdas", pos, Stdlib.FOR_FORM)
            out += if (fn in Stdlib.PRED_ARG) PredArg(pred(), pos) else ExprArg(expr(), pos)
            if (at(",")) p++ else return out
        }
    }

    /** `call("namespace", "operation", args?, options?)` */
    private fun call(): Expr {
        val kw = ident()
        expect("(")
        val ns = peek()
        if (ns.kind != TK.STRING) err("syntax.callForm", "the first argument of call is the namespace as a string", ns.pos, Stdlib.CALL_FORM)
        p++; expect(",")
        val op = peek()
        if (op.kind != TK.STRING) err("syntax.callForm", "the second argument of call is the operation name as a string", op.pos, Stdlib.CALL_FORM)
        p++
        var params: Expr? = null; var options: Expr? = null
        if (at(",")) { p++; params = expr(); if (at(",")) { p++; options = expr() } }
        if (at(",")) err("syntax.callForm", "call takes at most four arguments", peek().pos, Stdlib.CALL_FORM)
        expect(")")
        return OpCall(ns.text, op.text, params ?: RecordE(emptyList(), kw.pos), options, kw.pos)
    }

    private fun primary(): Expr {
        val tok = peek()
        return when {
            tok.kind == TK.STRING -> { p++; Lit(tok.text, tok.pos) }
            tok.kind == TK.NUMBER -> { p++; Lit(number(tok), tok.pos) }
            tok.kind == TK.IDENT && tok.text == "true" -> { p++; Lit(true, tok.pos) }
            tok.kind == TK.IDENT && tok.text == "false" -> { p++; Lit(false, tok.pos) }
            tok.kind == TK.IDENT && tok.text == "null" -> { p++; Lit(null, tok.pos) }
            at(".") || at("?.") -> {
                // a path from the implicit element; postfix loop continues it
                val root: Expr = Implicit(tok.pos)
                postfix(root)
            }
            at("(") -> { p++; val e = expr(); expect(")"); e }
            at("[") -> {
                p++
                val items = ArrayList<Expr>()
                while (!at("]")) { items += expr(); if (at(",")) p++ else break }
                expect("]")
                ListE(items, tok.pos)
            }
            at("{") -> braces()
            tok.kind == TK.IDENT && tok.text == "call" && peek(1).text == "(" -> call()
            tok.kind == TK.IDENT && tok.text == "for" -> forExpr()
            tok.kind == TK.IDENT && tok.text in Stdlib.KEYWORDS -> err("syntax.keyword", "'${tok.text}' is a keyword", tok.pos)
            tok.kind == TK.IDENT -> { p++; Ref(tok.text, tok.pos) }
            else -> err("syntax.unexpected", "unexpected '${tok.text.ifEmpty { "end of input" }}'")
        }
    }

    private fun number(tok: Token): Any =
        if (tok.text.contains('.') || tok.text.contains('e') || tok.text.contains('E')) tok.text.toDouble()
        else tok.text.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: tok.text.toDouble()

    /** `{` always opens a record; blocks are laid out by indentation (program, for body). */
    private fun braces(): Expr {
        val open = expect("{")
        if (at("}")) { p++; return RecordE(emptyList(), open.pos) }
        if (peek().kind == TK.IDENT && peek(1).text == "=")
            err("syntax.brace", "braces enclose a record { name: value }; bindings are not allowed inside them", peek().pos,
                "put bindings on their own lines: at the top level, or indented under a 'for x in xs' line, with the result last")
        if (peek().kind != TK.IDENT || peek(1).text != ":")
            err("syntax.brace", "braces enclose a record { name: value }; found '${peek().text}'", peek().pos, "to group an expression use parentheses; a for body is laid out by indentation")
        val fields = ArrayList<Pair<String, Expr>>()
        while (!at("}")) {
            val n = ident(); expect(":")
            fields += n.text to expr()
            if (at(",")) p++ else if (!at("}")) err("syntax.expected", "expected ',' or '}' in record")
        }
        expect("}")
        return RecordE(fields, open.pos)
    }

    // ── predicates ──────────────────────────────────────────────────────────

    fun pred(): Pred = predOr()

    private fun predOr(): Pred {
        val first = predAnd()
        if (!at("||")) return first
        val terms = arrayListOf(first)
        while (at("||")) { p++; terms += predAnd() }
        return OrP(terms, first.pos)
    }

    private fun predAnd(): Pred {
        val first = predNot()
        if (!at("&&")) return first
        val terms = arrayListOf(first)
        while (at("&&")) { p++; terms += predNot() }
        return AndP(terms, first.pos)
    }

    private fun predNot(): Pred {
        if (at("!")) { val pos = t[p++].pos; return NotP(predNot(), pos) }
        if (at("(")) {
            val save = p
            try { p++; val inner = pred(); expect(")"); return inner } catch (_: TowlException) { p = save }
        }
        return predAtom()
    }

    private fun predAtom(): Pred {
        val pos = peek().pos
        val operand = expr()
        val cmpOps = setOf("==", "!=", "<", "<=", ">", ">=")
        if (peek().kind == TK.OP && peek().text in cmpOps) {
            if (operand is MethodCall && operand.name in Stdlib.TEST && peek().text in setOf("==", "!=") && peek(1).kind == TK.IDENT && peek(1).text in setOf("true", "false")) {
                // `.L.empty() == false`: a test compared with a boolean is the test or its negation
                val negate = (peek().text == "==") != (peek(1).text == "true")
                p += 2
                val test = test(operand)
                return if (negate) NotP(test, pos) else test
            }
            val op = t[p++].text
            return Cmp(op, operand, expr(), pos)
        }
        if (atIdent("in")) { p++; return InP(operand, expr(), pos) }
        if (operand is MethodCall) {
            if (operand.name in Stdlib.TEST) return test(operand)
            if (operand.name == "any" || operand.name == "all") {
                val inner = (operand.args.singleOrNull() as? PredArg)?.pred ?: err("syntax.predicate", "${operand.name}(...) takes one predicate", operand.pos)
                return QuantP(operand.target, operand.name == "all", inner, operand.pos)
            }
        }
        err("syntax.predicate", "expected a predicate: <operand> == <operand>, <operand> in [...], .field.present(), .field.contains(\"x\"), .list.any(<pred>), joined with && || !", pos)
    }

    private fun test(operand: MethodCall): Pred {
        val arg = operand.args.singleOrNull()?.let { (it as? ExprArg)?.expr }
        if (operand.name in setOf("present", "absent", "empty") && operand.args.isNotEmpty()) err("syntax.predicate", "${operand.name}() takes no argument", operand.pos)
        if (operand.name !in setOf("present", "absent", "empty") && arg == null) err("syntax.predicate", "${operand.name}(s) takes one string argument", operand.pos)
        return TestP(operand.target, operand.name, arg, operand.pos)
    }
}

/** Utility: source text with the offending line, for diagnostics. */
fun excerpt(src: String, pos: Pos?): String? =
    pos?.let { src.lines().getOrNull(it.line - 1)?.trim() }
