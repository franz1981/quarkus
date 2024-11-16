package io.quarkus.bootstrap.runner;

class StringView {

    private final String s;

    private StringView(String s) {
        this.s = s;
    }

    private static final class SubStringView extends StringView {
        private final int hash;
        private final int length;

        private SubStringView(String fullString, int hash, int length) {
            super(fullString);
            this.hash = hash;
            this.length = length;
        }
    }

    /**
     * In theory the JIT is perfectly capable of performing bimorphic inlining here, if hot enough, but since
     * we expect to run this code while not yet fully warmed up, let's help it a bit by having a single implementation
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (!(o instanceof StringView otherView)) {
            return false;
        }
        final int length;
        if (this instanceof SubStringView thisSub) {
            length = thisSub.length;
        } else {
            length = s.length();
        }
        final int otherLength;
        if (o instanceof SubStringView otherSub) {
            otherLength = otherSub.length;
        } else {
            otherLength = otherView.s.length();
        }
        if (length != otherLength) {
            return false;
        }
        return regionMatches(s, otherView.s, length);
    }

    private static boolean regionMatches(String a, String b, int length) {
        if (length == a.length() && length == b.length()) {
            return a.equals(b);
        }
        // Intrinsified from https://github.com/openjdk/jdk/commit/861e302011bb3aaf0c8431c121b58a57b78481e3
        return a.regionMatches(0, b, 0, length);
    }

    public static final StringView EMPTY = new StringView("");

    public static StringView subOf(String s, int hashCode, int length) {
        // we're not performing any specific check at runtime since this is a likely cold path
        // and have to trust the data read from the serialized form
        assert validateView(s, hashCode, length);
        if (length == 0) {
            return EMPTY;
        }
        if (length == s.length()) {
            return new StringView(s);
        }
        if (length > s.length()) {
            throw new IllegalArgumentException("Length must be less than or equal to the full string length");
        }
        return new SubStringView(s, hashCode, length);
    }

    private static boolean validateView(String s, int hashCode, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length must be positive");
        }
        if (length > s.length()) {
            throw new IllegalArgumentException("Length must be less than or equal to the full string length");
        }
        if (s.substring(0, length).hashCode() != hashCode) {
            throw new IllegalArgumentException("Hash code does not match the substring hash code");
        }
        return true;
    }

    public static StringView of(String s) {
        return new StringView(s);
    }

    @Override
    public int hashCode() {
        if (this instanceof SubStringView sub) {
            return sub.hash;
        }
        return s.hashCode();
    }
}
