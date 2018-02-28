package org.cc11001100.select;

import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;
import org.junit.Test;

/**
 * @author CC11001100
 */
public class QueryParserTest {

    @Test
    public void test_001(){

        Evaluator evaluator = QueryParser.parse("a > b");
        System.out.println(evaluator);

    }

}
