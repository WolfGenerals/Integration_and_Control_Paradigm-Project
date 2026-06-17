/*
 * 表达式引擎 —— 用于驾驶技能 JSON 中的表达式求值。
 *
 * 支持运算符（优先级从高到低）：
 *   1. 一元：! + -
 *   2. 乘除：* /
 *   3. 加减：+ -
 *   4. 比较：== != > < >= <=
 *   5. 逻辑与：&&
 *   6. 逻辑或：||
 *   7. 三元：? :
 *
 * 标识符格式：命名空间.名称（如 input.forward, wheel.pos_z）
 * 自动类型转换：数值上下文中的布尔→0.0/1.0，布尔上下文中的数值→0=false
 */
package com.hainabaichuan75.iac_p.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;

/**
 * 表达式解析器和求值器。
 * <p>
 * 用法：
 * <pre>{@code
 *   Expr expr = SkillExpr.parse("input.forward && !input.backward");
 *   boolean result = expr.evalBool(context);
 * }</pre>
 */
public final class SkillExpr {

    // ==================================================================
    //  值类型
    // ==================================================================

    /**
     * 表达式求值结果，可以是数值或布尔值，支持自动类型转换。
     */
    public static final class Value {
        private final double num;
        private final boolean bool;
        private final boolean isBool;

        private Value(double num) {
            this.num = num;
            this.bool = num != 0.0;
            this.isBool = false;
        }

        private Value(boolean bool) {
            this.num = bool ? 1.0 : 0.0;
            this.bool = bool;
            this.isBool = true;
        }

        public static Value of(double v) { return new Value(v); }
        public static Value of(boolean v) { return new Value(v); }

        public double asDouble() { return num; }
        public boolean asBool() { return bool; }
        public boolean isBoolean() { return isBool; }

        @Override
        public String toString() {
            return isBool ? Boolean.toString(bool) : Double.toString(num);
        }
    }

    // ==================================================================
    //  AST 节点
    // ==================================================================

    public abstract static class Expr {
        /** 求值为数值（自动将布尔转为 0.0/1.0） */
        public abstract double evalDouble(Map<String, Value> ctx);
        /** 求值为布尔（自动将数值转为 true/false） */
        public abstract boolean evalBool(Map<String, Value> ctx);
    }

    private static final class NumLiteral extends Expr {
        final double value;
        NumLiteral(double v) { this.value = v; }
        @Override public double evalDouble(Map<String, Value> ctx) { return value; }
        @Override public boolean evalBool(Map<String, Value> ctx) { return value != 0; }
    }

    private static final class BoolLiteral extends Expr {
        final boolean value;
        BoolLiteral(boolean v) { this.value = v; }
        @Override public double evalDouble(Map<String, Value> ctx) { return value ? 1.0 : 0.0; }
        @Override public boolean evalBool(Map<String, Value> ctx) { return value; }
    }

    private static final class Ident extends Expr {
        final String name;
        Ident(String n) { this.name = n; }
        @Override
        public double evalDouble(Map<String, Value> ctx) {
            Value v = ctx.get(name);
            if (v == null) throw new EvalException("未定义的变量: " + name);
            return v.asDouble();
        }
        @Override
        public boolean evalBool(Map<String, Value> ctx) {
            Value v = ctx.get(name);
            if (v == null) throw new EvalException("未定义的变量: " + name);
            return v.asBool();
        }
    }

    private static final class UnaryOp extends Expr {
        final String op;
        final Expr operand;
        UnaryOp(String op, Expr e) { this.op = op; this.operand = e; }
        @Override
        public double evalDouble(Map<String, Value> ctx) {
            return switch (op) {
                case "+" -> +operand.evalDouble(ctx);
                case "-" -> -operand.evalDouble(ctx);
                case "!" -> operand.evalBool(ctx) ? 0.0 : 1.0;
                default -> throw new EvalException("未知一元运算符: " + op);
            };
        }
        @Override
        public boolean evalBool(Map<String, Value> ctx) {
            return switch (op) {
                case "!" -> !operand.evalBool(ctx);
                default -> evalDouble(ctx) != 0;
            };
        }
    }

    private static final class BinaryOp extends Expr {
        final String op;
        final Expr left, right;
        BinaryOp(String op, Expr l, Expr r) { this.op = op; this.left = l; this.right = r; }
        @Override
        public double evalDouble(Map<String, Value> ctx) {
            return switch (op) {
                case "+" -> left.evalDouble(ctx) + right.evalDouble(ctx);
                case "-" -> left.evalDouble(ctx) - right.evalDouble(ctx);
                case "*" -> left.evalDouble(ctx) * right.evalDouble(ctx);
                case "/" -> left.evalDouble(ctx) / right.evalDouble(ctx);
                case "&&" -> left.evalBool(ctx) && right.evalBool(ctx) ? 1.0 : 0.0;
                case "||" -> left.evalBool(ctx) || right.evalBool(ctx) ? 1.0 : 0.0;
                case "==" -> left.evalDouble(ctx) == right.evalDouble(ctx) ? 1.0 : 0.0;
                case "!=" -> left.evalDouble(ctx) != right.evalDouble(ctx) ? 1.0 : 0.0;
                case ">"  -> left.evalDouble(ctx) >  right.evalDouble(ctx) ? 1.0 : 0.0;
                case "<"  -> left.evalDouble(ctx) <  right.evalDouble(ctx) ? 1.0 : 0.0;
                case ">=" -> left.evalDouble(ctx) >= right.evalDouble(ctx) ? 1.0 : 0.0;
                case "<=" -> left.evalDouble(ctx) <= right.evalDouble(ctx) ? 1.0 : 0.0;
                default -> throw new EvalException("未知二元运算符: " + op);
            };
        }
        @Override
        public boolean evalBool(Map<String, Value> ctx) {
            return switch (op) {
                case "&&" -> left.evalBool(ctx) && right.evalBool(ctx);
                case "||" -> left.evalBool(ctx) || right.evalBool(ctx);
                default -> evalDouble(ctx) != 0;
            };
        }
    }

    private static final class TernaryOp extends Expr {
        final Expr cond, thenExpr, elseExpr;
        TernaryOp(Expr c, Expr t, Expr e) { this.cond = c; this.thenExpr = t; this.elseExpr = e; }
        @Override
        public double evalDouble(Map<String, Value> ctx) {
            return (cond.evalBool(ctx) ? thenExpr : elseExpr).evalDouble(ctx);
        }
        @Override
        public boolean evalBool(Map<String, Value> ctx) {
            return (cond.evalBool(ctx) ? thenExpr : elseExpr).evalBool(ctx);
        }
    }

    // ==================================================================
    //  异常
    // ==================================================================

    public static class EvalException extends RuntimeException {
        public EvalException(String msg) { super(msg); }
    }

    public static class ParseException extends RuntimeException {
        public final int position;
        public ParseException(String msg, int pos) {
            super("位置 " + pos + ": " + msg);
            this.position = pos;
        }
    }

    // ==================================================================
    //  词法分析器
    // ==================================================================

    private static final class Token {
        enum Type { NUMBER, IDENT, BOOL, OP, LPAREN, RPAREN, QUESTION, COLON, EOF }
        final Type type;
        final String text;
        final double number;
        Token(Type t, String s) { this.type = t; this.text = s; this.number = 0; }
        Token(double n) { this.type = Type.NUMBER; this.text = Double.toString(n); this.number = n; }
    }

    private static final class Lexer {
        private final String input;
        private int pos;

        Lexer(String s) { this.input = s; }

        Token peek() {
            int saved = pos;
            Token t = next();
            pos = saved;
            return t;
        }

        Token next() {
            skipSpace();
            if (pos >= input.length()) return new Token(Token.Type.EOF, "");

            char c = input.charAt(pos);

            // 数字
            if (isDigit(c) || (c == '.' && pos + 1 < input.length() && isDigit(input.charAt(pos + 1)))) {
                int start = pos;
                while (pos < input.length() && (isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) pos++;
                return new Token(Double.parseDouble(input.substring(start, pos)));
            }

            // 标识符或关键字
            if (isIdentStart(c)) {
                int start = pos;
                while (pos < input.length() && isIdentPart(input.charAt(pos))) pos++;
                String word = input.substring(start, pos);
                return switch (word) {
                    case "true" -> new Token(Token.Type.BOOL, "true");
                    case "false" -> new Token(Token.Type.BOOL, "false");
                    default -> new Token(Token.Type.IDENT, word);
                };
            }

            // 多字符运算符
            if (pos + 1 < input.length()) {
                String two = input.substring(pos, pos + 2);
                switch (two) {
                    case "&&" -> { pos += 2; return new Token(Token.Type.OP, "&&"); }
                    case "||" -> { pos += 2; return new Token(Token.Type.OP, "||"); }
                    case "==" -> { pos += 2; return new Token(Token.Type.OP, "=="); }
                    case "!=" -> { pos += 2; return new Token(Token.Type.OP, "!="); }
                    case ">=" -> { pos += 2; return new Token(Token.Type.OP, ">="); }
                    case "<=" -> { pos += 2; return new Token(Token.Type.OP, "<="); }
                }
            }

            // 单字符
            pos++;
            return switch (c) {
                case '(' -> new Token(Token.Type.LPAREN, "(");
                case ')' -> new Token(Token.Type.RPAREN, ")");
                case '?' -> new Token(Token.Type.QUESTION, "?");
                case ':' -> new Token(Token.Type.COLON, ":");
                case '+' -> new Token(Token.Type.OP, "+");
                case '-' -> new Token(Token.Type.OP, "-");
                case '*' -> new Token(Token.Type.OP, "*");
                case '/' -> new Token(Token.Type.OP, "/");
                case '>' -> new Token(Token.Type.OP, ">");
                case '<' -> new Token(Token.Type.OP, "<");
                case '!' -> new Token(Token.Type.OP, "!");
                default -> throw new ParseException("非法字符: '" + c + "'", pos - 1);
            };
        }

        private void skipSpace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
        private static boolean isIdentStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
        private static boolean isIdentPart(char c) { return isIdentStart(c) || isDigit(c) || c == '.'; }
    }

    // ==================================================================
    //  递归下降解析器
    // ==================================================================

    /**
     * 解析表达式字符串为 AST。
     */
    public static Expr parse(String input) {
        if (input == null || input.isBlank()) {
            return new BoolLiteral(false);
        }
        Lexer lexer = new Lexer(input.trim());
        Expr result = parseTernary(lexer);
        Token t = lexer.next();
        if (t.type != Token.Type.EOF) {
            throw new ParseException("表达式末尾有多余内容: " + t.text, lexer.pos);
        }
        return result;
    }

    // 三元: or_expr '?' expr ':' expr
    private static Expr parseTernary(Lexer lex) {
        Expr e = parseOr(lex);
        if (lex.peek().type == Token.Type.QUESTION) {
            lex.next(); // '?'
            Expr thenE = parseTernary(lex);
            if (lex.next().type != Token.Type.COLON)
                throw new ParseException("三元表达式缺少 ':'", lex.pos);
            Expr elseE = parseTernary(lex);
            e = new TernaryOp(e, thenE, elseE);
        }
        return e;
    }

    // 逻辑或: and_expr ('||' and_expr)*
    private static Expr parseOr(Lexer lex) {
        Expr e = parseAnd(lex);
        while (lex.peek().type == Token.Type.OP && "||".equals(lex.peek().text)) {
            lex.next();
            e = new BinaryOp("||", e, parseAnd(lex));
        }
        return e;
    }

    // 逻辑与: cmp_expr ('&&' cmp_expr)*
    private static Expr parseAnd(Lexer lex) {
        Expr e = parseCmp(lex);
        while (lex.peek().type == Token.Type.OP && "&&".equals(lex.peek().text)) {
            lex.next();
            e = new BinaryOp("&&", e, parseCmp(lex));
        }
        return e;
    }

    // 比较: add_expr (('=='|'!='|'>'|'<'|'>='|'<=') add_expr)?
    private static Expr parseCmp(Lexer lex) {
        Expr e = parseAdd(lex);
        Token t = lex.peek();
        if (t.type == Token.Type.OP && isCmpOp(t.text)) {
            lex.next();
            e = new BinaryOp(t.text, e, parseAdd(lex));
        }
        return e;
    }

    // 加减: mul_expr (('+'|'-') mul_expr)*
    private static Expr parseAdd(Lexer lex) {
        Expr e = parseMul(lex);
        while (true) {
            Token t = lex.peek();
            if (t.type == Token.Type.OP && ("+".equals(t.text) || "-".equals(t.text))) {
                lex.next();
                e = new BinaryOp(t.text, e, parseMul(lex));
            } else break;
        }
        return e;
    }

    // 乘除: unary_expr (('*'|'/') unary_expr)*
    private static Expr parseMul(Lexer lex) {
        Expr e = parseUnary(lex);
        while (true) {
            Token t = lex.peek();
            if (t.type == Token.Type.OP && ("*".equals(t.text) || "/".equals(t.text))) {
                lex.next();
                e = new BinaryOp(t.text, e, parseUnary(lex));
            } else break;
        }
        return e;
    }

    // 一元: '!' unary_expr | '+' unary_expr | '-' unary_expr | primary
    private static Expr parseUnary(Lexer lex) {
        Token t = lex.peek();
        if (t.type == Token.Type.OP && ("!".equals(t.text) || "+".equals(t.text) || "-".equals(t.text))) {
            lex.next();
            return new UnaryOp(t.text, parseUnary(lex));
        }
        return parsePrimary(lex);
    }

    // 基本: NUMBER | BOOL | IDENT | '(' expr ')'
    private static Expr parsePrimary(Lexer lex) {
        Token t = lex.next();
        return switch (t.type) {
            case NUMBER -> new NumLiteral(t.number);
            case BOOL -> new BoolLiteral("true".equals(t.text));
            case IDENT -> new Ident(t.text);
            case LPAREN -> {
                Expr e = parseTernary(lex);
                Token close = lex.next();
                if (close.type != Token.Type.RPAREN)
                    throw new ParseException("缺少 ')'", lex.pos);
                yield e;
            }
            default -> throw new ParseException("预期表达式，但遇到: " + t.text, lex.pos);
        };
    }

    private static boolean isCmpOp(String s) {
        return "==".equals(s) || "!=".equals(s) || ">".equals(s) || "<".equals(s) || ">=".equals(s) || "<=".equals(s);
    }

    // ==================================================================
    //  工具：快捷求值
    // ==================================================================

    /**
     * 解析并求值一个表达式，返回 double。
     */
    public static double evalDouble(String expr, Map<String, Value> ctx) {
        return parse(expr).evalDouble(ctx);
    }

    /**
     * 解析并求值一个表达式，返回 boolean。
     */
    public static boolean evalBool(String expr, Map<String, Value> ctx) {
        return parse(expr).evalBool(ctx);
    }

    /**
     * 从布尔输入映射构建求值上下文。
     *
     * @param inputs 输入名称 → 布尔值
     * @return 求值上下文
     */
    public static Map<String, Value> contextFromInputs(Map<String, Boolean> inputs) {
        var builder = new java.util.HashMap<String, Value>(inputs.size());
        for (var e : inputs.entrySet()) {
            builder.put(e.getKey(), Value.of(e.getValue()));
        }
        return Map.copyOf(builder);
    }

    /**
     * 合并额外变量到上下文。
     */
    public static Map<String, Value> extendContext(Map<String, Value> base, Map<String, Value> extra) {
        var merged = new java.util.HashMap<String, Value>(base);
        merged.putAll(extra);
        return Map.copyOf(merged);
    }

    private SkillExpr() {}
}
