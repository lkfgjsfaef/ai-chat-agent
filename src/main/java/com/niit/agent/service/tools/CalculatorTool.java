package com.niit.agent.service.tools;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CalculatorTool implements ToolHandler {

    @Override
    public String getName() {
        return "calculate";
    }

    @Override
    public String getDescription() {
        return "执行数学计算，支持加减乘除、括号、幂运算(^)、sqrt、abs。适用于需要精确数值计算的场景。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> expression = new HashMap<>();
        expression.put("type", "string");
        expression.put("description", "数学表达式，例如: (100+200)*0.05, 2^8, sqrt(144), abs(-5)");
        properties.put("expression", expression);

        parameters.put("properties", properties);
        parameters.put("required", List.of("expression"));
        return parameters;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String expression = (String) params.get("expression");
        if (expression == null || expression.trim().isEmpty()) {
            return "错误：必须提供 expression 参数";
        }

        try {
            double result = evaluate(expression.trim());
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return "错误：计算结果无效";
            }
            if (result == Math.floor(result) && Math.abs(result) < 1e15) {
                return String.format("计算结果: %d", (long) result);
            }
            return String.format("计算结果: %.6f", result);
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }

    private double evaluate(String expr) {
        return new ExprParser(expr).parse();
    }

    private static class ExprParser {
        private final String input;
        private int pos;

        ExprParser(String input) {
            this.input = input.replaceAll("\\s+", "");
            this.pos = 0;
        }

        double parse() {
            double result = parseAddSub();
            if (pos < input.length()) {
                throw new IllegalArgumentException("表达式在位置 " + pos + " 处有多余字符: '" + input.charAt(pos) + "'");
            }
            return result;
        }

        private double parseAddSub() {
            double left = parseMulDiv();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op != '+' && op != '-') break;
                pos++;
                double right = parseMulDiv();
                left = op == '+' ? left + right : left - right;
            }
            return left;
        }

        private double parseMulDiv() {
            double left = parsePower();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op != '*' && op != '/' && op != '%') break;
                pos++;
                double right = parsePower();
                if (op == '*') left *= right;
                else if (op == '/') {
                    if (right == 0) throw new ArithmeticException("除数不能为零");
                    left /= right;
                } else left %= right;
            }
            return left;
        }

        private double parsePower() {
            double left = parseUnary();
            while (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                double right = parseUnary();
                left = Math.pow(left, right);
            }
            return left;
        }

        private double parseUnary() {
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return -parseAtom();
            }
            return parseAtom();
        }

        private double parseAtom() {
            if (pos >= input.length()) {
                throw new IllegalArgumentException("表达式不完整，缺少操作数");
            }

            char c = input.charAt(pos);

            if (c == '(') {
                pos++;
                double result = parseAddSub();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++;
                return result;
            }

            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }

            if (Character.isLetter(c)) {
                String name = readIdentifier();
                if (name.equals("sqrt")) {
                    if (pos >= input.length() || input.charAt(pos) != '(') {
                        throw new IllegalArgumentException("sqrt 后需要括号");
                    }
                    pos++;
                    double arg = parseAddSub();
                    if (pos >= input.length() || input.charAt(pos) != ')') {
                        throw new IllegalArgumentException("sqrt 缺少右括号");
                    }
                    pos++;
                    if (arg < 0) throw new IllegalArgumentException("sqrt 参数不能为负数");
                    return Math.sqrt(arg);
                }
                if (name.equals("abs")) {
                    if (pos >= input.length() || input.charAt(pos) != '(') {
                        throw new IllegalArgumentException("abs 后需要括号");
                    }
                    pos++;
                    double arg = parseAddSub();
                    if (pos >= input.length() || input.charAt(pos) != ')') {
                        throw new IllegalArgumentException("abs 缺少右括号");
                    }
                    pos++;
                    return Math.abs(arg);
                }
                if (name.equals("pi")) return Math.PI;
                if (name.equals("e")) return Math.E;
                throw new IllegalArgumentException("未知函数或常量: " + name);
            }

            throw new IllegalArgumentException("意外的字符 '" + c + "' 在位置 " + pos);
        }

        private double parseNumber() {
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(input.substring(start, pos));
        }

        private String readIdentifier() {
            int start = pos;
            while (pos < input.length() && Character.isLetter(input.charAt(pos))) {
                pos++;
            }
            return input.substring(start, pos);
        }
    }
}
