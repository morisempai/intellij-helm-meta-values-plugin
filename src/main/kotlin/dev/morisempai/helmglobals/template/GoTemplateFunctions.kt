package dev.morisempai.helmglobals.template

/**
 * The Go template built-ins and Sprig functions Helm makes available in a chart, as completion and
 * documentation material.
 *
 * This is deliberately a fixed catalogue rather than an attempt at understanding the template
 * language: charts can add their own helpers with `define`, so an unknown name is never treated as
 * an error, it simply is not offered.
 */
object GoTemplateFunctions {

    enum class Kind { FUNCTION, ACTION }

    data class Entry(
        val name: String,
        /** Arguments as they read in a call, empty for actions taking none. */
        val arguments: String,
        val description: String,
        val kind: Kind = Kind.FUNCTION,
    ) {
        val signature: String get() = if (arguments.isEmpty()) name else "$name $arguments"
    }

    private fun fn(name: String, arguments: String, description: String) =
        Entry(name, arguments, description)

    private fun action(name: String, arguments: String, description: String) =
        Entry(name, arguments, description, Kind.ACTION)

    val entries: List<Entry> = listOf(
        // Actions -----------------------------------------------------------------------------
        action("if", "PIPELINE", "Renders the block when the pipeline is non-empty."),
        action("else", "", "Alternative branch of an if, with or range."),
        action("else if", "PIPELINE", "Chained conditional branch."),
        action("end", "", "Closes an if, with, range, define or block."),
        action("range", "PIPELINE", "Iterates over a list, map or channel."),
        action("with", "PIPELINE", "Rebinds the dot to the pipeline when it is non-empty."),
        action("define", "\"NAME\"", "Defines a named template."),
        action("template", "\"NAME\" PIPELINE", "Renders a named template; does not pipe."),
        action("block", "\"NAME\" PIPELINE", "Defines and immediately renders a template."),

        // Go built-ins ------------------------------------------------------------------------
        fn("and", "ARG…", "First empty argument, or the last one."),
        fn("or", "ARG…", "First non-empty argument, or the last one."),
        fn("not", "ARG", "Boolean negation."),
        fn("eq", "A B", "True when the arguments are equal."),
        fn("ne", "A B", "True when the arguments differ."),
        fn("lt", "A B", "True when A < B."),
        fn("le", "A B", "True when A <= B."),
        fn("gt", "A B", "True when A > B."),
        fn("ge", "A B", "True when A >= B."),
        fn("index", "COLLECTION KEY…", "Element of a list or map, indexed successively."),
        fn("len", "COLLECTION", "Length of a string, list or map."),
        fn("slice", "LIST START END", "Sub-slice of a list or string."),
        fn("print", "ARG…", "Concatenates the arguments, like fmt.Sprint."),
        fn("printf", "FORMAT ARG…", "Formats the arguments, like fmt.Sprintf."),
        fn("println", "ARG…", "Concatenates the arguments and appends a newline."),
        fn("call", "FUNC ARG…", "Calls a function value with the given arguments."),
        fn("urlquery", "VALUE", "URL query escaping."),

        // Defaults and emptiness ---------------------------------------------------------------
        fn("default", "FALLBACK VALUE", "The value, or the fallback when the value is empty."),
        fn("empty", "VALUE", "True when the value is empty for its type."),
        fn("coalesce", "ARG…", "First non-empty argument."),
        fn("ternary", "IF_TRUE IF_FALSE CONDITION", "Picks one of two values on a boolean."),
        fn("required", "MESSAGE VALUE", "Fails the render with the message when the value is empty."),

        // Strings -------------------------------------------------------------------------------
        fn("quote", "VALUE…", "Wraps in double quotes."),
        fn("squote", "VALUE…", "Wraps in single quotes."),
        fn("upper", "STRING", "Uppercases the string."),
        fn("lower", "STRING", "Lowercases the string."),
        fn("title", "STRING", "Title-cases the string."),
        fn("untitle", "STRING", "Lowercases the first letter of each word."),
        fn("trim", "STRING", "Removes leading and trailing whitespace."),
        fn("trimAll", "CUTSET STRING", "Removes the given characters from both ends."),
        fn("trimPrefix", "PREFIX STRING", "Removes the prefix if present."),
        fn("trimSuffix", "SUFFIX STRING", "Removes the suffix if present."),
        fn("trunc", "LENGTH STRING", "Truncates to a length; a negative length keeps the tail."),
        fn("repeat", "COUNT STRING", "Repeats the string."),
        fn("substr", "START END STRING", "Substring between two offsets."),
        fn("contains", "SUBSTRING STRING", "True when the string contains the substring."),
        fn("hasPrefix", "PREFIX STRING", "True when the string starts with the prefix."),
        fn("hasSuffix", "SUFFIX STRING", "True when the string ends with the suffix."),
        fn("replace", "OLD NEW STRING", "Replaces every occurrence."),
        fn("split", "SEPARATOR STRING", "Splits into a map keyed _0, _1, …"),
        fn("splitList", "SEPARATOR STRING", "Splits into a list."),
        fn("join", "SEPARATOR LIST", "Joins a list into a string."),
        fn("cat", "ARG…", "Concatenates the arguments with spaces."),
        fn("indent", "WIDTH STRING", "Indents every line by the given number of spaces."),
        fn("nindent", "WIDTH STRING", "Like indent, but starts with a newline."),
        fn("toString", "VALUE", "Converts the value to a string."),

        // Serialisation ---------------------------------------------------------------------------
        fn("toYaml", "VALUE", "Renders the value as YAML."),
        fn("toJson", "VALUE", "Renders the value as JSON."),
        fn("toPrettyJson", "VALUE", "Renders the value as indented JSON."),
        fn("fromYaml", "STRING", "Parses YAML into a structure."),
        fn("fromJson", "STRING", "Parses JSON into a structure."),
        fn("b64enc", "STRING", "Base64 encoding."),
        fn("b64dec", "STRING", "Base64 decoding."),
        fn("sha256sum", "STRING", "SHA-256 of the string, hex encoded."),

        // Collections -----------------------------------------------------------------------------
        fn("list", "ARG…", "Builds a list."),
        fn("dict", "KEY VALUE…", "Builds a map from alternating keys and values."),
        fn("get", "MAP KEY", "Value for a key, or an empty string."),
        fn("set", "MAP KEY VALUE", "Adds a key to a map and returns the map."),
        fn("hasKey", "MAP KEY", "True when the map contains the key."),
        fn("keys", "MAP…", "Keys of the maps."),
        fn("values", "MAP", "Values of the map."),
        fn("pluck", "KEY MAP…", "Value of the key from each map."),
        fn("dig", "KEY… FALLBACK MAP", "Walks nested maps, falling back when a key is absent."),
        fn("merge", "DESTINATION SOURCE…", "Merges maps; existing keys win."),
        fn("mergeOverwrite", "DESTINATION SOURCE…", "Merges maps; later sources win."),
        fn("deepCopy", "VALUE", "Deep copy of the value."),
        fn("first", "LIST", "First element."),
        fn("last", "LIST", "Last element."),
        fn("compact", "LIST", "Drops empty elements."),
        fn("uniq", "LIST", "Drops duplicates."),
        fn("sortAlpha", "LIST", "Sorts alphabetically."),

        // Numbers ---------------------------------------------------------------------------------
        fn("add", "ARG…", "Sum of the arguments."),
        fn("sub", "A B", "A minus B."),
        fn("mul", "ARG…", "Product of the arguments."),
        fn("div", "A B", "Integer division."),
        fn("mod", "A B", "Remainder."),
        fn("max", "ARG…", "Largest argument."),
        fn("min", "ARG…", "Smallest argument."),
        fn("int", "VALUE", "Converts to an integer."),
        fn("float64", "VALUE", "Converts to a float."),
        fn("atoi", "STRING", "Parses a string as an integer."),

        // Helm-specific -----------------------------------------------------------------------------
        fn("include", "\"NAME\" PIPELINE", "Renders a named template and returns it as a string."),
        fn("tpl", "STRING CONTEXT", "Renders a string as a template."),
        fn("lookup", "API_VERSION KIND NAMESPACE NAME", "Reads a live object from the cluster."),
        fn("semverCompare", "CONSTRAINT VERSION", "True when the version satisfies the constraint."),
        fn("typeOf", "VALUE", "Go type name of the value."),
        fn("kindIs", "KIND VALUE", "True when the value is of the given kind."),
        fn("regexMatch", "PATTERN STRING", "True when the pattern matches."),
        fn("regexReplaceAll", "PATTERN STRING REPLACEMENT", "Replaces every match."),
        fn("now", "", "Current time."),
        fn("date", "FORMAT TIME", "Formats a time."),
        fn("genCA", "SUBJECT DAYS", "Generates a self-signed certificate authority."),
        fn("randAlphaNum", "LENGTH", "Random alphanumeric string."),
    )

    private val byName: Map<String, Entry> = entries.associateBy { it.name }

    operator fun get(name: String): Entry? = byName[name]

    fun contains(name: String): Boolean = byName.containsKey(name)
}
