package org.jsoup.select;

import org.jsoup.helper.StringUtil;
import org.jsoup.helper.Validate;
import org.jsoup.parser.TokenQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jsoup.internal.Normalizer.normalize;

/**
 * 将CSS Selector解析为执行器
 * <p>
 * Parses a CSS selector into an Evaluator tree.
 */
public class QueryParser {

    // 使用的是组合选择器
    private final static String[] COMBINATORS = {",", ">", "+", "~", " "};
    private static final String[] ATTRIBUTE_EVALUATORS = new String[]{"=", "!=", "^=", "$=", "*=", "~="};

    private TokenQueue tokenQueue;
    private String cssQuery;
    private List<Evaluator> evaluatorList = new ArrayList<>();

    /**
     * Create a new QueryParser.
     *
     * @param cssQuery CSS cssQuery
     */
    private QueryParser(String cssQuery) {
        this.cssQuery = cssQuery;
        this.tokenQueue = new TokenQueue(cssQuery);
    }

    /**
     * Parse a CSS cssQuery into an Evaluator.
     *
     * @param cssQuery CSS cssQuery
     * @return Evaluator
     */
    public static Evaluator parse(String cssQuery) {
        try {
            QueryParser p = new QueryParser(cssQuery);
            return p.parse();
        } catch (IllegalArgumentException e) {
            throw new Selector.SelectorParseException(e.getMessage());
        }
    }

    /**
     * Parse the cssQuery
     *
     * @return Evaluator
     */
    Evaluator parse() {
        tokenQueue.consumeWhitespace();

        if (tokenQueue.matchesAny(COMBINATORS)) { // if starts with a combinator, use root as elements
            evaluatorList.add(new StructuralEvaluator.Root());
            combinator(tokenQueue.consume());
        } else {
            findElements();
        }

        while (!tokenQueue.isEmpty()) {
            // hierarchy and extras
            boolean seenWhite = tokenQueue.consumeWhitespace();

            if (tokenQueue.matchesAny(COMBINATORS)) {
                combinator(tokenQueue.consume());
            } else if (seenWhite) {
                combinator(' ');
            } else { // E.class, E#id, E[attr] etc. AND
                findElements(); // take next el, #. etc off queue
            }
        }

        if (evaluatorList.size() == 1)
            return evaluatorList.get(0);

        return new CombiningEvaluator.And(evaluatorList);
    }

    private void combinator(char combinator) {
        tokenQueue.consumeWhitespace();
        String subQuery = consumeSubQuery(); // support multi > childs

        Evaluator rootEval; // the new topmost evaluator
        Evaluator currentEval; // the evaluator the new eval will be combined to. could be root, or rightmost or.
        Evaluator newEval = parse(subQuery); // the evaluator to add into target evaluator
        boolean replaceRightMost = false;

        if (evaluatorList.size() == 1) {
            rootEval = currentEval = evaluatorList.get(0);
            // make sure OR (,) has precedence:
            if (rootEval instanceof CombiningEvaluator.Or && combinator != ',') {
                currentEval = ((CombiningEvaluator.Or) currentEval).rightMostEvaluator();
                replaceRightMost = true;
            }
        } else {
            rootEval = currentEval = new CombiningEvaluator.And(evaluatorList);
        }
        evaluatorList.clear();

        // for most COMBINATORS: change the current eval into an AND of the current eval and the new eval
        if (combinator == '>')
            currentEval = new CombiningEvaluator.And(newEval, new StructuralEvaluator.ImmediateParent(currentEval));
        else if (combinator == ' ')
            currentEval = new CombiningEvaluator.And(newEval, new StructuralEvaluator.Parent(currentEval));
        else if (combinator == '+')
            currentEval = new CombiningEvaluator.And(newEval, new StructuralEvaluator.ImmediatePreviousSibling(currentEval));
        else if (combinator == '~')
            currentEval = new CombiningEvaluator.And(newEval, new StructuralEvaluator.PreviousSibling(currentEval));
        else if (combinator == ',') { // group or.
            CombiningEvaluator.Or or;
            if (currentEval instanceof CombiningEvaluator.Or) {
                or = (CombiningEvaluator.Or) currentEval;
                or.add(newEval);
            } else {
                or = new CombiningEvaluator.Or();
                or.add(currentEval);
                or.add(newEval);
            }
            currentEval = or;
        } else
            throw new Selector.SelectorParseException("Unknown combinator: " + combinator);

        if (replaceRightMost)
            ((CombiningEvaluator.Or) rootEval).replaceRightMostEvaluator(currentEval);
        else rootEval = currentEval;
        evaluatorList.add(rootEval);
    }

    private String consumeSubQuery() {
        StringBuilder sq = new StringBuilder();
        while (!tokenQueue.isEmpty()) {
            if (tokenQueue.matches("("))
                sq.append("(").append(tokenQueue.chompBalanced('(', ')')).append(")");
            else if (tokenQueue.matches("["))
                sq.append("[").append(tokenQueue.chompBalanced('[', ']')).append("]");
            else if (tokenQueue.matchesAny(COMBINATORS))
                break;
            else
                sq.append(tokenQueue.consume());
        }
        return sq.toString();
    }

    private void findElements() {
        if (tokenQueue.matchChomp("#"))
            byId();
        else if (tokenQueue.matchChomp("."))
            byClass();
        else if (tokenQueue.matchesWord() || tokenQueue.matches("*|"))
            byTag();
        else if (tokenQueue.matches("["))
            byAttribute();
        else if (tokenQueue.matchChomp("*"))
            allElements();
        else if (tokenQueue.matchChomp(":lt("))
            indexLessThan();
        else if (tokenQueue.matchChomp(":gt("))
            indexGreaterThan();
        else if (tokenQueue.matchChomp(":eq("))
            indexEquals();
        else if (tokenQueue.matches(":has("))
            has();
        else if (tokenQueue.matches(":contains("))
            contains(false);
        else if (tokenQueue.matches(":containsOwn("))
            contains(true);
        else if (tokenQueue.matches(":containsData("))
            containsData();
        else if (tokenQueue.matches(":matches("))
            matches(false);
        else if (tokenQueue.matches(":matchesOwn("))
            matches(true);
        else if (tokenQueue.matches(":not("))
            not();
        else if (tokenQueue.matchChomp(":nth-child("))
            cssNthChild(false, false);
        else if (tokenQueue.matchChomp(":nth-last-child("))
            cssNthChild(true, false);
        else if (tokenQueue.matchChomp(":nth-of-type("))
            cssNthChild(false, true);
        else if (tokenQueue.matchChomp(":nth-last-of-type("))
            cssNthChild(true, true);
        else if (tokenQueue.matchChomp(":first-child"))
            evaluatorList.add(new Evaluator.IsFirstChild());
        else if (tokenQueue.matchChomp(":last-child"))
            evaluatorList.add(new Evaluator.IsLastChild());
        else if (tokenQueue.matchChomp(":first-of-type"))
            evaluatorList.add(new Evaluator.IsFirstOfType());
        else if (tokenQueue.matchChomp(":last-of-type"))
            evaluatorList.add(new Evaluator.IsLastOfType());
        else if (tokenQueue.matchChomp(":only-child"))
            evaluatorList.add(new Evaluator.IsOnlyChild());
        else if (tokenQueue.matchChomp(":only-of-type"))
            evaluatorList.add(new Evaluator.IsOnlyOfType());
        else if (tokenQueue.matchChomp(":empty"))
            evaluatorList.add(new Evaluator.IsEmpty());
        else if (tokenQueue.matchChomp(":root"))
            evaluatorList.add(new Evaluator.IsRoot());
        else if (tokenQueue.matchChomp(":matchText"))
            evaluatorList.add(new Evaluator.MatchText());
        else // unhandled
            throw new Selector.SelectorParseException("Could not parse cssQuery '%s': unexpected token at '%s'", cssQuery, tokenQueue.remainder());
    }

    private void byId() {
        String id = tokenQueue.consumeCssIdentifier();
        Validate.notEmpty(id);
        evaluatorList.add(new Evaluator.Id(id));
    }

    private void byClass() {
        String className = tokenQueue.consumeCssIdentifier();
        Validate.notEmpty(className);
        evaluatorList.add(new Evaluator.Class(className.trim()));
    }

    private void byTag() {
        String tagName = tokenQueue.consumeElementSelector();

        Validate.notEmpty(tagName);

        // namespaces: wildcard match equals(tagName) or ending in ":"+tagName
        if (tagName.startsWith("*|")) {
            // 标签名或任意命名空间下的标签
            evaluatorList.add(new CombiningEvaluator.Or(new Evaluator.Tag(normalize(tagName)), new Evaluator.TagEndsWith(normalize(tagName.replace("*|", ":")))));
        } else {
            // namespaces: if element name is "abc:def", selector must be "abc|def", so flip:
            if (tagName.contains("|"))
                tagName = tagName.replace("|", ":");

            evaluatorList.add(new Evaluator.Tag(tagName.trim()));
        }
    }

    private void byAttribute() {
        TokenQueue cq = new TokenQueue(tokenQueue.chompBalanced('[', ']')); // content queue
        // key is attr name
        String key = cq.consumeToAny(ATTRIBUTE_EVALUATORS); // eq, not, start, end, contain, match, (no val)
        Validate.notEmpty(key);
        cq.consumeWhitespace();

        if (cq.isEmpty()) {
            // [^<key>\\s*] | [<key>\\s*] | [^<key><evaluator>\\s*] | [<key><evaluator>\\s*]
            // 反正后面没啥了，<evaluator>即使有也可以直接忽略了，所以可以直接认为是选取属性是否存在的
            if (key.startsWith("^"))
                // 取反的
                evaluatorList.add(new Evaluator.AttributeStarting(key.substring(1)));
            else
                evaluatorList.add(new Evaluator.Attribute(key));
        } else {
            // 说明有属性值
            if (cq.matchChomp("="))
                evaluatorList.add(new Evaluator.AttributeWithValue(key, cq.remainder()));
            else if (cq.matchChomp("!="))
                evaluatorList.add(new Evaluator.AttributeWithValueNot(key, cq.remainder()));
            else if (cq.matchChomp("^="))
                evaluatorList.add(new Evaluator.AttributeWithValueStarting(key, cq.remainder()));
            else if (cq.matchChomp("$="))
                evaluatorList.add(new Evaluator.AttributeWithValueEnding(key, cq.remainder()));
            else if (cq.matchChomp("*="))
                evaluatorList.add(new Evaluator.AttributeWithValueContaining(key, cq.remainder()));
            else if (cq.matchChomp("~="))
                evaluatorList.add(new Evaluator.AttributeWithValueMatching(key, Pattern.compile(cq.remainder())));
            else
                throw new Selector.SelectorParseException("Could not parse attribute cssQuery '%s': unexpected token at '%s'", cssQuery, cq.remainder());
        }
    }

    private void allElements() {
        evaluatorList.add(new Evaluator.AllElements());
    }

    // pseudo selectors :lt, :gt, :eq
    private void indexLessThan() {
        evaluatorList.add(new Evaluator.IndexLessThan(consumeIndex()));
    }

    private void indexGreaterThan() {
        evaluatorList.add(new Evaluator.IndexGreaterThan(consumeIndex()));
    }

    private void indexEquals() {
        evaluatorList.add(new Evaluator.IndexEquals(consumeIndex()));
    }

    // 伪类选择器
    //pseudo selectors :first-child, :last-child, :nth-child, ...
    private static final Pattern NTH_AB = Pattern.compile("(([+-])?(\\d+)?)n(\\s*([+-])?\\s*\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NTH_B = Pattern.compile("([+-])?(\\d+)");

    private void cssNthChild(boolean backwards, boolean ofType) {
        String argS = normalize(tokenQueue.chompTo(")"));
        Matcher mAB = NTH_AB.matcher(argS);
        Matcher mB = NTH_B.matcher(argS);
        final int a, b;
        if ("odd".equals(argS)) {
            a = 2;
            b = 1;
        } else if ("even".equals(argS)) {
            a = 2;
            b = 0;
        } else if (mAB.matches()) {
            a = mAB.group(3) != null ? Integer.parseInt(mAB.group(1).replaceFirst("^\\+", "")) : 1;
            b = mAB.group(4) != null ? Integer.parseInt(mAB.group(4).replaceFirst("^\\+", "")) : 0;
        } else if (mB.matches()) {
            a = 0;
            b = Integer.parseInt(mB.group().replaceFirst("^\\+", ""));
        } else {
            throw new Selector.SelectorParseException("Could not parse nth-index '%s': unexpected format", argS);
        }
        if (ofType)
            if (backwards)
                evaluatorList.add(new Evaluator.IsNthLastOfType(a, b));
            else
                evaluatorList.add(new Evaluator.IsNthOfType(a, b));
        else {
            if (backwards)
                evaluatorList.add(new Evaluator.IsNthLastChild(a, b));
            else
                evaluatorList.add(new Evaluator.IsNthChild(a, b));
        }
    }

    // consume index and right bracket
    private int consumeIndex() {
        String indexS = tokenQueue.chompTo(")").trim();
        Validate.isTrue(StringUtil.isNumeric(indexS), "Index must be numeric");
        return Integer.parseInt(indexS);
    }

    // pseudo selector :has(el)
    private void has() {
        tokenQueue.consume(":has");
        String subQuery = tokenQueue.chompBalanced('(', ')');
        Validate.notEmpty(subQuery, ":has(el) subselect must not be empty");
        evaluatorList.add(new StructuralEvaluator.Has(parse(subQuery)));
    }

    // pseudo selector :contains(text), containsOwn(text)
    private void contains(boolean own) {
        tokenQueue.consume(own ? ":containsOwn" : ":contains");
        String searchText = TokenQueue.unescape(tokenQueue.chompBalanced('(', ')'));
        Validate.notEmpty(searchText, ":contains(text) cssQuery must not be empty");
        if (own)
            evaluatorList.add(new Evaluator.ContainsOwnText(searchText));
        else
            evaluatorList.add(new Evaluator.ContainsText(searchText));
    }

    // pseudo selector :containsData(data)
    private void containsData() {
        tokenQueue.consume(":containsData");
        String searchText = TokenQueue.unescape(tokenQueue.chompBalanced('(', ')'));
        Validate.notEmpty(searchText, ":containsData(text) cssQuery must not be empty");
        evaluatorList.add(new Evaluator.ContainsData(searchText));
    }

    // :matches(regex), matchesOwn(regex)
    private void matches(boolean own) {
        tokenQueue.consume(own ? ":matchesOwn" : ":matches");
        String regex = tokenQueue.chompBalanced('(', ')'); // don't unescape, as regex bits will be escaped
        Validate.notEmpty(regex, ":matches(regex) cssQuery must not be empty");

        if (own)
            evaluatorList.add(new Evaluator.MatchesOwn(Pattern.compile(regex)));
        else
            evaluatorList.add(new Evaluator.Matches(Pattern.compile(regex)));
    }

    // :not(selector)
    private void not() {
        tokenQueue.consume(":not");
        String subQuery = tokenQueue.chompBalanced('(', ')');
        Validate.notEmpty(subQuery, ":not(selector) subselect must not be empty");

        evaluatorList.add(new StructuralEvaluator.Not(parse(subQuery)));
    }

}
