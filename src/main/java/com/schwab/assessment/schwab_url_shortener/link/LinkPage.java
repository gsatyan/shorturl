package com.schwab.assessment.schwab_url_shortener.link;

import java.util.List;

/**
 * One page of the caller's links, newest first.
 *
 * @param items      at most {@code limit} links
 * @param nextCursor pass as {@code cursor} to get the next page; {@code null} on the last page
 */
public record LinkPage(List<LinkResponse> items, String nextCursor) {
}
