package org.ashkeshadek.reader;

import java.util.List;

/** Prepared query variants shared by the strict scorer and the aligner. */
class SearchQuery {
    final String normalized, modern, phonetic;
    final List<SearchTerm> terms;

    SearchQuery(String normalized, String modern, String phonetic, List<SearchTerm> terms) {
        this.normalized = normalized; this.modern = modern; this.phonetic = phonetic; this.terms = terms;
    }

    static class SearchTerm {
        final String original, modern, phonetic;
        SearchTerm(String original, String modern, String phonetic) {
            this.original = original; this.modern = modern; this.phonetic = phonetic;
        }
    }
}
