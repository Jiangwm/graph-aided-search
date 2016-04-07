/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.integration.es.booster;

import com.graphaware.integration.es.GraphAidedSearch;
import com.graphaware.integration.es.IndexInfo;
import com.graphaware.integration.es.domain.ExternalResult;
import com.graphaware.integration.es.util.NumberUtil;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;

import java.util.*;

import static com.graphaware.integration.es.domain.Constants.*;

public abstract class SearchResultExternalBooster implements SearchResultBooster {

    private final String neo4jHost;
    private final int maxResultWindow;

    private int maxResultSize = -1;

    private int size;
    private int from;

    protected String composeScoreOperator;

    protected static final String DEFAULT_SCORE_OPERATOR = "*";

    public SearchResultExternalBooster(Settings settings, IndexInfo indexSettings) {
        this.neo4jHost = indexSettings.getNeo4jHost();
        this.maxResultWindow = indexSettings.getMaxResultWindow();
    }

    public final void parseRequest(Map<String, Object> sourceAsMap) {
        size = NumberUtil.getInt(sourceAsMap.get(SIZE), 10);
        from = NumberUtil.getInt(sourceAsMap.get(FROM), 0);

        Map<String, String> extParams = (Map<String, String>) sourceAsMap.get(GraphAidedSearch.GAS_BOOSTER_CLAUSE);
        if (extParams != null) {
            maxResultSize = NumberUtil.getInt(extParams.get(MAX_RESULT_SIZE), maxResultWindow);
            composeScoreOperator = extParams.get(OPERATOR) != null ? extParams.get(OPERATOR) : DEFAULT_SCORE_OPERATOR;
            extendedParseRequest(extParams);
            validateOperator();
        }
        if (maxResultSize > 0) {
            sourceAsMap.put(SIZE, maxResultSize);
        }
        sourceAsMap.put(FROM, 0);
    }

    public InternalSearchHits modify(final InternalSearchHits hits) {
        final InternalSearchHit[] searchHits = hits.internalHits();
        Map<String, InternalSearchHit> hitMap = new HashMap<>();
        for (InternalSearchHit hit : searchHits) {
            hitMap.put(hit.getId(), hit);
        }
        int totalHitsSize = hitMap.keySet().size();
        Map<String, ExternalResult> remoteScore = externalDoReorder(hitMap.keySet());
        final int arraySize = (size + from) < searchHits.length ? size
                : (searchHits.length - from) > 0 ? (searchHits.length - from) : 0;
        if (arraySize == 0) {
            return new InternalSearchHits(new InternalSearchHit[0], 0, 0);
        }

        final int totalSize = arraySize + from;
        List<InternalSearchHit> newSearchHits = new ArrayList<>(totalSize);
        float maxScore = -1;
        for (Map.Entry<String, InternalSearchHit> item : hitMap.entrySet()) {
            ExternalResult remoteResult = remoteScore.get(item.getKey());
            if (remoteResult != null) {
                float newScore = composeScore(item.getValue().score(), remoteResult.getScore());
                if (maxScore < newScore) {
                    maxScore = newScore;
                }
                item.getValue().score(newScore);
            }
            int k = 0;
            while (newSearchHits.size() > 0
                    && k < newSearchHits.size()
                    && newSearchHits.get(k) != null
                    && newSearchHits.get(k).score() > item.getValue().score()
                    && k < totalSize) {
                k++;
            }
            if (k < totalSize) {
                newSearchHits.add(k, item.getValue());
            }
            if (newSearchHits.size() > totalSize) {
                newSearchHits.remove(totalSize);
            }
        }
        if (from > 0) {
            int k = 0;
            while (k < from) {
                newSearchHits.remove(0);
                k++;
            }
        }
        return new InternalSearchHits(newSearchHits.toArray(new InternalSearchHit[arraySize]), totalHitsSize,
                maxScore);
    }

    protected float composeScore(float esScore, float extScore) {
        switch (getComposeScoreOperator()) {
            case MULTIPLY:
                return esScore * extScore;
            case DIVIDE:
                return esScore / extScore;
            case PLUS:
                return esScore + extScore;
            case MINUS:
                return esScore - extScore;
            case REPLACE:
                return extScore;
            default:
                return esScore;
        }

    }

    public int getSize() {
        return size;
    }

    public int getFrom() {
        return from;
    }

    public int getMaxResultSize() {
        return maxResultSize;
    }

    protected abstract Map<String, ExternalResult> externalDoReorder(Set<String> keySet);

    protected String getNeo4jHost() {
        return neo4jHost;
    }

    protected int getMaxResultWindow() {
        return maxResultWindow;
    }

    protected void extendedParseRequest(Map<String, String> extParams) {

    }

    protected void validateOperator() {
        Set<String> validOperators = new HashSet<>();
        validOperators.add(MULTIPLY);
        validOperators.add(PLUS);
        validOperators.add(MINUS);
        validOperators.add(DIVIDE);
        validOperators.add(REPLACE);

        String operator = getComposeScoreOperator();

        if (!validOperators.contains(operator)) {
            throw new IllegalArgumentException("Operator \"" + operator + "\" is not valid");
        }
    }

    protected String getComposeScoreOperator() {
        return composeScoreOperator != null ? composeScoreOperator : DEFAULT_SCORE_OPERATOR;
    }
}
