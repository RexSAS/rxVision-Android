package com.andrea.rxvision;

import java.util.Arrays;
import java.util.List;

public class SearchTest {
    public static void main(String[] args) {
        List<String> apps = Arrays.asList("Camera", "Claude", "Google Home", "rxhome", "WhatsApp", "Pronto Treno");

        assert Search.rank(apps, "").equals(Arrays.asList(0, 1, 2, 3, 4, 5)) : "empty query lists all in order";
        assert Search.rank(apps, "cl").equals(Arrays.asList(1)) : "prefix";
        assert Search.rank(apps, "  CLA ").equals(Arrays.asList(1)) : "case and spaces ignored";
        // "home": word prefix in Google Home beats substring in rxhome
        assert Search.rank(apps, "home").equals(Arrays.asList(2, 3)) : Search.rank(apps, "home");
        assert Search.rank(apps, "wapp").equals(Arrays.asList(4)) : "letters in order";
        assert Search.rank(apps, "c").equals(Arrays.asList(0, 1)) : "ties keep list order";
        assert Search.rank(apps, "tr").equals(Arrays.asList(5)) : "second word";
        assert Search.rank(apps, "zz").isEmpty() : "no match";
    }
}
