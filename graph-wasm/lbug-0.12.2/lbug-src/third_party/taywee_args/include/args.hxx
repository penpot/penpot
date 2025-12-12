/* A simple header-only C++ argument parser library.
 *
 * https://github.com/Taywee/args
 *
 * Copyright (c) 2016-2021 Taylor C. Richberger <taywee@gmx.com> and Pavel
 * Belikov
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

/** \file args.hxx
 * \brief this single-header lets you use all of the args functionality
 *
 * The important stuff is done inside the args namespace
 */

#ifndef ARGS_HXX
#define ARGS_HXX

#define ARGS_VERSION "6.4.2"
#define ARGS_VERSION_MAJOR 6
#define ARGS_VERSION_MINOR 4
#define ARGS_VERSION_PATCH 2

#include <algorithm>
#include <iterator>
#include <exception>
#include <functional>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <type_traits>
#include <cstddef>
#include <cctype>
#include <iostream>

#if defined(_MSC_VER) && _MSC_VER <= 1800
#define noexcept
#endif

#ifdef ARGS_TESTNAMESPACE
namespace argstest
{
#else

/** \namespace args
 * \brief contains all the functionality of the args library
 */
namespace args
{
#endif
    /** Getter to grab the value from the argument type.
     *
     * If the Get() function of the type returns a reference, so does this, and
     * the value will be modifiable.
     */
    template <typename Option>
    auto get(Option &option_) -> decltype(option_.Get())
    {
        return option_.Get();
    }

    /** (INTERNAL) Count UTF-8 glyphs
     *
     * This is not reliable, and will fail for combinatory glyphs, but it's
     * good enough here for now.
     *
     * \param string The string to count glyphs from
     * \return The UTF-8 glyphs in the string
     */
    inline std::string::size_type Glyphs(const std::string &string_)
    {
        std::string::size_type length = 0;
        for (const char c: string_)
        {
            if ((c & 0xc0) != 0x80)
            {
                ++length;
            }
        }
        return length;
    }

    /** (INTERNAL) Wrap a vector of words into a vector of lines
     *
     * Empty words are skipped. Word "\n" forces wrapping.
     *
     * \param begin The begin iterator
     * \param end The end iterator
     * \param width The width of the body
     * \param firstlinewidth the width of the first line, defaults to the width of the body
     * \param firstlineindent the indent of the first line, defaults to 0
     * \return the vector of lines
     */
    template <typename It>
    inline std::vector<std::string> Wrap(It begin,
                                         It end,
                                         const std::string::size_type width,
                                         std::string::size_type firstlinewidth = 0,
                                         std::string::size_type firstlineindent = 0)
    {
        std::vector<std::string> output;
        std::string line(firstlineindent, ' ');
        bool empty = true;

        if (firstlinewidth == 0)
        {
            firstlinewidth = width;
        }

        auto currentwidth = firstlinewidth;

        for (auto it = begin; it != end; ++it)
        {
            if (it->empty())
            {
                continue;
            }

            if (*it == "\n")
            {
                if (!empty)
                {
                    output.push_back(line);
                    line.clear();
                    empty = true;
                    currentwidth = width;
                }

                continue;
            }

            auto itemsize = Glyphs(*it);
            if ((line.length() + 1 + itemsize) > currentwidth)
            {
                if (!empty)
                {
                    output.push_back(line);
                    line.clear();
                    empty = true;
                    currentwidth = width;
                }
            }

            if (itemsize > 0)
            {
                if (!empty)
                {
                    line += ' ';
                }

                line += *it;
                empty = false;
            }
        }

        if (!empty)
        {
            output.push_back(line);
        }

        return output;
    }

    namespace detail
    {
        template <typename T>
        std::string Join(const T& array, const std::string &delimiter)
        {
            std::string res;
            for (auto &element : array)
            {
                if (!res.empty())
                {
                    res += delimiter;
                }

                res += element;
            }

            return res;
        }
    }

    /** (INTERNAL) Wrap a string into a vector of lines
     *
     * This is quick and hacky, but works well enough.  You can specify a
     * different width for the first line
     *
     * \param width The width of the body
     * \param firstlinewid the width of the first line, defaults to the width of the body
     * \return the vector of lines
     */
    inline std::vector<std::string> Wrap(const std::string &in, const std::string::size_type width, std::string::size_type firstlinewidth = 0)
    {
        // Preserve existing line breaks
        const auto newlineloc = in.find('\n');
        if (newlineloc != in.npos)
        {
            auto first = Wrap(std::string(in, 0, newlineloc), width);
            auto second = Wrap(std::string(in, newlineloc + 1), width);
            first.insert(
                std::end(first),
                std::make_move_iterator(std::begin(second)),
                std::make_move_iterator(std::end(second)));
            return first;
        }

        std::istringstream stream(in);
        std::string::size_type indent = 0;

        for (auto c : in)
        {
            if (!std::isspace(static_cast<unsigned char>(c)))
            {
                break;
            }
            ++indent;
        }

        return Wrap(std::istream_iterator<std::string>(stream), std::istream_iterator<std::string>(),
                    width, firstlinewidth, indent);
    }

#ifdef ARGS_NOEXCEPT
    /// Error class, for when ARGS_NOEXCEPT is defined
    enum class Error
    {
        None,
        Usage,
        Parse,
        Validation,
        Required,
        Map,
        Extra,
        Help,
        Subparser,
        Completion,
    };
#else
    /** Base error class
     */
    class Error : public std::runtime_error
    {
        public:
            Error(const std::string &problem) : std::runtime_error(problem) {}
            virtual ~Error() {}
    };

    /** Errors that occur during usage
     */
    class UsageError : public Error
    {
        public:
            UsageError(const std::string &problem) : Error(problem) {}
            virtual ~UsageError() {}
    };

    /** Errors that occur during regular parsing
     */
    class ParseError : public Error
    {
        public:
            ParseError(const std::string &problem) : Error(problem) {}
            virtual ~ParseError() {}
    };

    /** Errors that are detected from group validation after parsing finishes
     */
    class ValidationError : public Error
    {
        public:
            ValidationError(const std::string &problem) : Error(problem) {}
            virtual ~ValidationError() {}
    };

    /** Errors that when a required flag is omitted
     */
    class RequiredError : public ValidationError
    {
        public:
            RequiredError(const std::string &problem) : ValidationError(problem) {}
            virtual ~RequiredError() {}
    };

    /** Errors in map lookups
     */
    class MapError : public ParseError
    {
        public:
            MapError(const std::string &problem) : ParseError(problem) {}
            virtual ~MapError() {}
    };

    /** Error that occurs when a singular flag is specified multiple times
     */
    class ExtraError : public ParseError
    {
        public:
            ExtraError(const std::string &problem) : ParseError(problem) {}
            virtual ~ExtraError() {}
    };

    /** An exception that indicates that the user has requested help
     */
    class Help : public Error
    {
        public:
            Help(const std::string &flag) : Error(flag) {}
            virtual ~Help() {}
    };

    /** (INTERNAL) An exception that emulates coroutine-like control flow for subparsers.
     */
    class SubparserError : public Error
    {
        public:
            SubparserError() : Error("") {}
            virtual ~SubparserError() {}
    };

    /** An exception that contains autocompletion reply
     */
    class Completion : public Error
    {
        public:
            Completion(const std::string &flag) : Error(flag) {}
            virtual ~Completion() {}
    };
#endif

    /** A simple unified option type for unified initializer lists for the Matcher class.
     */
    struct EitherFlag
    {
        const bool isShort;
        const char shortFlag;
        const std::string longFlag;
        EitherFlag(const std::string &flag) : isShort(false), shortFlag(), longFlag(flag) {}
        EitherFlag(const char *flag) : isShort(false), shortFlag(), longFlag(flag) {}
        EitherFlag(const char flag) : isShort(true), shortFlag(flag), longFlag() {}

        /** Get just the long flags from an initializer list of EitherFlags
         */
        static std::unordered_set<std::string> GetLong(std::initializer_list<EitherFlag> flags)
        {
            std::unordered_set<std::string>  longFlags;
            for (const EitherFlag &flag: flags)
            {
                if (!flag.isShort)
                {
                    longFlags.insert(flag.longFlag);
                }
            }
            return longFlags;
        }

        /** Get just the short flags from an initializer list of EitherFlags
         */
        static std::unordered_set<char> GetShort(std::initializer_list<EitherFlag> flags)
        {
            std::unordered_set<char>  shortFlags;
            for (const EitherFlag &flag: flags)
            {
                if (flag.isShort)
                {
                    shortFlags.insert(flag.shortFlag);
                }
            }
            return shortFlags;
        }

        std::string str() const
        {
            return isShort ? std::string(1, shortFlag) : longFlag;
        }

        std::string str(const std::string &shortPrefix, const std::string &longPrefix) const
        {
            return isShort ? shortPrefix + std::string(1, shortFlag) : longPrefix + longFlag;
        }
    };



    /** A class of "matchers", specifying short and flags that can possibly be
     * matched.
     *
     * This is supposed to be constructed and then passed in, not used directly
     * from user code.
     */
    class Matcher
    {
        private:
            const std::unordered_set<char> shortFlags;
            const std::unordered_set<std::string> longFlags;

        public:
            /** Specify short and long flags separately as iterators
             *
             * ex: `args::Matcher(shortFlags.begin(), shortFlags.end(), longFlags.begin(), longFlags.end())`
             */
            template <typename ShortIt, typename LongIt>
            Matcher(ShortIt shortFlagsStart, ShortIt shortFlagsEnd, LongIt longFlagsStart, LongIt longFlagsEnd) :
                shortFlags(shortFlagsStart, shortFlagsEnd),
                longFlags(longFlagsStart, longFlagsEnd)
            {
                if (shortFlags.empty() && longFlags.empty())
                {
#ifndef ARGS_NOEXCEPT
                    throw UsageError("empty Matcher");
#endif
                }
            }

#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            Error GetError() const noexcept
            {
                return shortFlags.empty() && longFlags.empty() ? Error::Usage : Error::None;
            }
#endif

            /** Specify short and long flags separately as iterables
             *
             * ex: `args::Matcher(shortFlags, longFlags)`
             */
            template <typename Short, typename Long>
            Matcher(Short &&shortIn, Long &&longIn) :
                Matcher(std::begin(shortIn), std::end(shortIn), std::begin(longIn), std::end(longIn))
            {}

            /** Specify a mixed single initializer-list of both short and long flags
             *
             * This is the fancy one.  It takes a single initializer list of
             * any number of any mixed kinds of flags.  Chars are
             * automatically interpreted as short flags, and strings are
             * automatically interpreted as long flags:
             *
             *     args::Matcher{'a'}
             *     args::Matcher{"foo"}
             *     args::Matcher{'h', "help"}
             *     args::Matcher{"foo", 'f', 'F', "FoO"}
             */
            Matcher(std::initializer_list<EitherFlag> in) :
                Matcher(EitherFlag::GetShort(in), EitherFlag::GetLong(in)) {}

            Matcher(Matcher &&other) noexcept : shortFlags(std::move(other.shortFlags)), longFlags(std::move(other.longFlags))
            {}

            ~Matcher() {}

            /** (INTERNAL) Check if there is a match of a short flag
             */
            bool Match(const char flag) const
            {
                return shortFlags.find(flag) != shortFlags.end();
            }

            /** (INTERNAL) Check if there is a match of a long flag
             */
            bool Match(const std::string &flag) const
            {
                return longFlags.find(flag) != longFlags.end();
            }

            /** (INTERNAL) Check if there is a match of a flag
             */
            bool Match(const EitherFlag &flag) const
            {
                return flag.isShort ? Match(flag.shortFlag) : Match(flag.longFlag);
            }

            /** (INTERNAL) Get all flag strings as a vector, with the prefixes embedded
             */
            std::vector<EitherFlag> GetFlagStrings() const
            {
                std::vector<EitherFlag> flagStrings;
                flagStrings.reserve(shortFlags.size() + longFlags.size());
                for (const char flag: shortFlags)
                {
                    flagStrings.emplace_back(flag);
                }
                for (const std::string &flag: longFlags)
                {
                    flagStrings.emplace_back(flag);
                }
                return flagStrings;
            }

            /** (INTERNAL) Get long flag if it exists or any short flag
             */
            EitherFlag GetLongOrAny() const
            {
                if (!longFlags.empty())
                {
                    return *longFlags.begin();
                }

                if (!shortFlags.empty())
                {
                    return *shortFlags.begin();
                }

                // should be unreachable
                return ' ';
            }

            /** (INTERNAL) Get short flag if it exists or any long flag
             */
            EitherFlag GetShortOrAny() const
            {
                if (!shortFlags.empty())
                {
                    return *shortFlags.begin();
                }

                if (!longFlags.empty())
                {
                    return *longFlags.begin();
                }

                // should be unreachable
                return ' ';
            }
    };

    /** Attributes for flags.
     */
    enum class Options
    {
        /** Default options.
         */
        None = 0x0,

        /** Flag can't be passed multiple times.
         */
        Single = 0x01,

        /** Flag can't be omitted.
         */
        Required = 0x02,

        /** Flag is excluded from usage line.
         */
        HiddenFromUsage = 0x04,

        /** Flag is excluded from options help.
         */
        HiddenFromDescription = 0x08,

        /** Flag is global and can be used in any subcommand.
         */
        Global = 0x10,

        /** Flag stops a parser.
         */
        KickOut = 0x20,

        /** Flag is excluded from auto completion.
         */
        HiddenFromCompletion = 0x40,

        /** Flag is excluded from options help and usage line
         */
        Hidden = HiddenFromUsage | HiddenFromDescription | HiddenFromCompletion,
    };

    inline Options operator | (Options lhs, Options rhs)
    {
        return static_cast<Options>(static_cast<int>(lhs) | static_cast<int>(rhs));
    }

    inline Options operator & (Options lhs, Options rhs)
    {
        return static_cast<Options>(static_cast<int>(lhs) & static_cast<int>(rhs));
    }

    class FlagBase;
    class PositionalBase;
    class Command;
    class ArgumentParser;

    /** A simple structure of parameters for easy user-modifyable help menus
     */
    struct HelpParams
    {
        /** The width of the help menu
         */
        unsigned int width = 80;
        /** The indent of the program line
         */
        unsigned int progindent = 2;
        /** The indent of the program trailing lines for long parameters
         */
        unsigned int progtailindent = 4;
        /** The indent of the description and epilogs
         */
        unsigned int descriptionindent = 4;
        /** The indent of the flags
         */
        unsigned int flagindent = 6;
        /** The indent of the flag descriptions
         */
        unsigned int helpindent = 40;
        /** The additional indent each group adds
         */
        unsigned int eachgroupindent = 2;

        /** The minimum gutter between each flag and its help
         */
        unsigned int gutter = 1;

        /** Show the terminator when both options and positional parameters are present
         */
        bool showTerminator = true;

        /** Show the {OPTIONS} on the prog line when this is true
         */
        bool showProglineOptions = true;

        /** Show the positionals on the prog line when this is true
         */
        bool showProglinePositionals = true;

        /** The prefix for short flags
         */
        std::string shortPrefix;

        /** The prefix for long flags
         */
        std::string longPrefix;

        /** The separator for short flags
         */
        std::string shortSeparator;

        /** The separator for long flags
         */
        std::string longSeparator;

        /** The program name for help generation
         */
        std::string programName;

        /** Show command's flags
         */
        bool showCommandChildren = false;

        /** Show command's descriptions and epilog
         */
        bool showCommandFullHelp = false;

        /** The postfix for progline when showProglineOptions is true and command has any flags
         */
        std::string proglineOptions = "{OPTIONS}";

        /** The prefix for progline when command has any subcommands
         */
        std::string proglineCommand = "COMMAND";

        /** The prefix for progline value
         */
        std::string proglineValueOpen = " <";

        /** The postfix for progline value
         */
        std::string proglineValueClose = ">";

        /** The prefix for progline required argument
         */
        std::string proglineRequiredOpen = "";

        /** The postfix for progline required argument
         */
        std::string proglineRequiredClose = "";

        /** The prefix for progline non-required argument
         */
        std::string proglineNonrequiredOpen = "[";

        /** The postfix for progline non-required argument
         */
        std::string proglineNonrequiredClose = "]";

        /** Show flags in program line
         */
        bool proglineShowFlags = false;

        /** Use short flags in program lines when possible
         */
        bool proglinePreferShortFlags = false;

        /** Program line prefix
         */
        std::string usageString;

        /** String shown in help before flags descriptions
         */
        std::string optionsString = "OPTIONS:";

        /** Display value name after all the long and short flags
         */
        bool useValueNameOnce = false;

        /** Show value name
         */
        bool showValueName = true;

        /** Add newline before flag description
         */
        bool addNewlineBeforeDescription = false;

        /** The prefix for option value
         */
        std::string valueOpen = "[";

        /** The postfix for option value
         */
        std::string valueClose = "]";

        /** Add choices to argument description
         */
        bool addChoices = false;

        /** The prefix for choices
         */
        std::string choiceString = "\nOne of: ";

        /** Add default values to argument description
         */
        bool addDefault = false;

        /** The prefix for default values
         */
        std::string defaultString = "\nDefault: ";
    };

    /** A number of arguments which can be consumed by an option.
     *
     * Represents a closed interval [min, max].
     */
    struct Nargs
    {
        const size_t min;
        const size_t max;

        Nargs(size_t min_, size_t max_) : min{min_}, max{max_}
        {
#ifndef ARGS_NOEXCEPT
            if (max < min)
            {
                throw UsageError("Nargs: max > min");
            }
#endif
        }

        Nargs(size_t num_) : min{num_}, max{num_}
        {
        }

        friend bool operator == (const Nargs &lhs, const Nargs &rhs)
        {
            return lhs.min == rhs.min && lhs.max == rhs.max;
        }

        friend bool operator != (const Nargs &lhs, const Nargs &rhs)
        {
            return !(lhs == rhs);
        }
    };

    /** Base class for all match types
     */
    class Base
    {
        private:
            Options options = {};

        protected:
            bool matched = false;
            const std::string help;
#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            mutable Error error = Error::None;
            mutable std::string errorMsg;
#endif

        public:
            Base(const std::string &help_, Options options_ = {}) : options(options_), help(help_) {}
            virtual ~Base() {}

            Options GetOptions() const noexcept
            {
                return options;
            }

            bool IsRequired() const noexcept
            {
                return (GetOptions() & Options::Required) != Options::None;
            }

            virtual bool Matched() const noexcept
            {
                return matched;
            }

            virtual void Validate(const std::string &, const std::string &) const
            {
            }

            operator bool() const noexcept
            {
                return Matched();
            }

            virtual std::vector<std::tuple<std::string, std::string, unsigned>> GetDescription(const HelpParams &, const unsigned indentLevel) const
            {
                std::tuple<std::string, std::string, unsigned> description;
                std::get<1>(description) = help;
                std::get<2>(description) = indentLevel;
                return { std::move(description) };
            }

            virtual std::vector<Command*> GetCommands()
            {
                return {};
            }

            virtual bool IsGroup() const
            {
                return false;
            }

            virtual FlagBase *Match(const EitherFlag &)
            {
                return nullptr;
            }

            virtual PositionalBase *GetNextPositional()
            {
                return nullptr;
            }

            virtual std::vector<FlagBase*> GetAllFlags()
            {
                return {};
            }

            virtual bool HasFlag() const
            {
                return false;
            }

            virtual bool HasPositional() const
            {
                return false;
            }

            virtual bool HasCommand() const
            {
                return false;
            }

            virtual std::vector<std::string> GetProgramLine(const HelpParams &) const
            {
                return {};
            }

            /// Sets a kick-out value for building subparsers
            void KickOut(bool kickout_) noexcept
            {
                if (kickout_)
                {
                    options = options | Options::KickOut;
                }
                else
                {
                    options = static_cast<Options>(static_cast<int>(options) & ~static_cast<int>(Options::KickOut));
                }
            }

            /// Gets the kick-out value for building subparsers
            bool KickOut() const noexcept
            {
                return (options & Options::KickOut) != Options::None;
            }

            virtual void Reset() noexcept
            {
                matched = false;
#ifdef ARGS_NOEXCEPT
                error = Error::None;
                errorMsg.clear();
#endif
            }

#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            virtual Error GetError() const
            {
                return error;
            }

            /// Only for ARGS_NOEXCEPT
            virtual std::string GetErrorMsg() const
            {
                return errorMsg;
            }
#endif
    };

    /** Base class for all match types that have a name
     */
    class NamedBase : public Base
    {
        protected:
            const std::string name;
            bool kickout = false;
            std::string defaultString;
            bool defaultStringManual = false;
            std::vector<std::string> choicesStrings;
            bool choicesStringManual = false;

            virtual std::string GetDefaultString(const HelpParams&) const { return {}; }

            virtual std::vector<std::string> GetChoicesStrings(const HelpParams&) const { return {}; }

            virtual std::string GetNameString(const HelpParams&) const { return Name(); }

            void AddDescriptionPostfix(std::string &dest, const bool isManual, const std::string &manual, bool isGenerated, const std::string &generated, const std::string &str) const
            {
                if (isManual && !manual.empty())
                {
                    dest += str;
                    dest += manual;
                }
                else if (!isManual && isGenerated && !generated.empty())
                {
                    dest += str;
                    dest += generated;
                }
            }

        public:
            NamedBase(const std::string &name_, const std::string &help_, Options options_ = {}) : Base(help_, options_), name(name_) {}
            virtual ~NamedBase() {}

            /** Sets default value string that will be added to argument description.
             *  Use empty string to disable it for this argument.
             */
            void HelpDefault(const std::string &str)
            {
                defaultStringManual = true;
                defaultString = str;
            }

            /** Gets default value string that will be added to argument description.
             */
            std::string HelpDefault(const HelpParams &params) const
            {
                return defaultStringManual ? defaultString : GetDefaultString(params);
            }

            /** Sets choices strings that will be added to argument description.
             *  Use empty vector to disable it for this argument.
             */
            void HelpChoices(const std::vector<std::string> &array)
            {
                choicesStringManual = true;
                choicesStrings = array;
            }

            /** Gets choices strings that will be added to argument description.
             */
            std::vector<std::string> HelpChoices(const HelpParams &params) const
            {
                return choicesStringManual ? choicesStrings : GetChoicesStrings(params);
            }

            virtual std::vector<std::tuple<std::string, std::string, unsigned>> GetDescription(const HelpParams &params, const unsigned indentLevel) const override
            {
                std::tuple<std::string, std::string, unsigned> description;
                std::get<0>(description) = GetNameString(params);
                std::get<1>(description) = help;
                std::get<2>(description) = indentLevel;

                AddDescriptionPostfix(std::get<1>(description), choicesStringManual, detail::Join(choicesStrings, ", "), params.addChoices, detail::Join(GetChoicesStrings(params), ", "), params.choiceString);
                AddDescriptionPostfix(std::get<1>(description), defaultStringManual, defaultString, params.addDefault, GetDefaultString(params), params.defaultString);

                return { std::move(description) };
            }

            virtual std::string Name() const
            {
                return name;
            }
    };

    namespace detail
    {
        template<typename T>
        using vector = std::vector<T, std::allocator<T>>;
        
        template<typename K, typename T>
        using unordered_map = std::unordered_map<K, T, std::hash<K>, 
            std::equal_to<K>, std::allocator<std::pair<const K, T> > >;

        template<typename S, typename T>
        class is_streamable
        {
            template<typename SS, typename TT>
            static auto test(int)
            -> decltype( std::declval<SS&>() << std::declval<TT>(), std::true_type() );

            template<typename, typename>
            static auto test(...) -> std::false_type;

        public:
            using type = decltype(test<S,T>(0));
        };

        template <typename T>
        using IsConvertableToString = typename is_streamable<std::ostringstream, T>::type;

        template <typename T>
        typename std::enable_if<IsConvertableToString<T>::value, std::string>::type
        ToString(const T &value)
        {
            std::ostringstream s;
            s << value;
            return s.str();
        }

        template <typename T>
        typename std::enable_if<!IsConvertableToString<T>::value, std::string>::type
        ToString(const T &)
        {
            return {};
        }

        template <typename T>
        std::vector<std::string> MapKeysToStrings(const T &map)
        {
            std::vector<std::string> res;
            using K = typename std::decay<decltype(std::begin(map)->first)>::type;
            if (IsConvertableToString<K>::value)
            {
                for (const auto &p : map)
                {
                    res.push_back(detail::ToString(p.first));
                }

                std::sort(res.begin(), res.end());
            }
            return res;
        }
    }

    /** Base class for all flag options
     */
    class FlagBase : public NamedBase
    {
        protected:
            const Matcher matcher;

            virtual std::string GetNameString(const HelpParams &params) const override
            {
                const std::string postfix = !params.showValueName || NumberOfArguments() == 0 ? std::string() : Name();
                std::string flags;
                const auto flagStrings = matcher.GetFlagStrings();
                const bool useValueNameOnce = flagStrings.size() == 1 ? false : params.useValueNameOnce;
                for (auto it = flagStrings.begin(); it != flagStrings.end(); ++it)
                {
                    auto &flag = *it;
                    if (it != flagStrings.begin())
                    {
                        flags += ", ";
                    }

                    flags += flag.isShort ? params.shortPrefix : params.longPrefix;
                    flags += flag.str();

                    if (!postfix.empty() && (!useValueNameOnce || it + 1 == flagStrings.end()))
                    {
                        flags += flag.isShort ? params.shortSeparator : params.longSeparator;
                        flags += params.valueOpen + postfix + params.valueClose;
                    }
                }

                return flags;
            }

        public:
            FlagBase(const std::string &name_, const std::string &help_, Matcher &&matcher_, const bool extraError_ = false) : NamedBase(name_, help_, extraError_ ? Options::Single : Options()), matcher(std::move(matcher_)) {}

            FlagBase(const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_) : NamedBase(name_, help_, options_), matcher(std::move(matcher_)) {}

            virtual ~FlagBase() {}

            virtual FlagBase *Match(const EitherFlag &flag) override
            {
                if (matcher.Match(flag))
                {
                    if ((GetOptions() & Options::Single) != Options::None && matched)
                    {
                        std::ostringstream problem;
                        problem << "Flag '" << flag.str() << "' was passed multiple times, but is only allowed to be passed once";
#ifdef ARGS_NOEXCEPT
                        error = Error::Extra;
                        errorMsg = problem.str();
#else
                        throw ExtraError(problem.str());
#endif
                    }
                    matched = true;
                    return this;
                }
                return nullptr;
            }

            virtual std::vector<FlagBase*> GetAllFlags() override
            {
                return { this };
            }

            const Matcher &GetMatcher() const
            {
                return matcher;
            }

            virtual void Validate(const std::string &shortPrefix, const std::string &longPrefix) const override
            {
                if (!Matched() && IsRequired())
                {
                        std::ostringstream problem;
                        problem << "Flag '" << matcher.GetLongOrAny().str(shortPrefix, longPrefix) << "' is required";
#ifdef ARGS_NOEXCEPT
                        error = Error::Required;
                        errorMsg = problem.str();
#else
                        throw RequiredError(problem.str());
#endif
                }
            }

            virtual std::vector<std::string> GetProgramLine(const HelpParams &params) const override
            {
                if (!params.proglineShowFlags)
                {
                    return {};
                }

                const std::string postfix = NumberOfArguments() == 0 ? std::string() : Name();
                const EitherFlag flag = params.proglinePreferShortFlags ? matcher.GetShortOrAny() : matcher.GetLongOrAny();
                std::string res = flag.str(params.shortPrefix, params.longPrefix);
                if (!postfix.empty())
                {
                    res += params.proglineValueOpen + postfix + params.proglineValueClose;
                }

                return { IsRequired() ? params.proglineRequiredOpen + res + params.proglineRequiredClose
                                      : params.proglineNonrequiredOpen + res + params.proglineNonrequiredClose };
            }

            virtual bool HasFlag() const override
            {
                return true;
            }

#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            virtual Error GetError() const override
            {
                const auto nargs = NumberOfArguments();
                if (nargs.min > nargs.max)
                {
                    return Error::Usage;
                }

                const auto matcherError = matcher.GetError();
                if (matcherError != Error::None)
                {
                    return matcherError;
                }

                return error;
            }
#endif

            /** Defines how many values can be consumed by this option.
             *
             * \return closed interval [min, max]
             */
            virtual Nargs NumberOfArguments() const noexcept = 0;

            /** Parse values of this option.
             *
             * \param value Vector of values. It's size must be in NumberOfArguments() interval.
             */
            virtual void ParseValue(const std::vector<std::string> &value) = 0;
    };

    /** Base class for value-accepting flag options
     */
    class ValueFlagBase : public FlagBase
    {
        public:
            ValueFlagBase(const std::string &name_, const std::string &help_, Matcher &&matcher_, const bool extraError_ = false) : FlagBase(name_, help_, std::move(matcher_), extraError_) {}
            ValueFlagBase(const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_) : FlagBase(name_, help_, std::move(matcher_), options_) {}
            virtual ~ValueFlagBase() {}

            virtual Nargs NumberOfArguments() const noexcept override
            {
                return 1;
            }
    };

    class CompletionFlag : public ValueFlagBase
    {
        public:
            std::vector<std::string> reply;
            size_t cword = 0;
            std::string syntax;

            template <typename GroupClass>
            CompletionFlag(GroupClass &group_, Matcher &&matcher_): ValueFlagBase("completion", "completion flag", std::move(matcher_), Options::Hidden)
            {
                group_.AddCompletion(*this);
            }

            virtual ~CompletionFlag() {}

            virtual Nargs NumberOfArguments() const noexcept override
            {
                return 2;
            }

            virtual void ParseValue(const std::vector<std::string> &value_) override
            {
                syntax = value_.at(0);
                std::istringstream(value_.at(1)) >> cword;
            }

            /** Get the completion reply
             */
            std::string Get() noexcept
            {
                return detail::Join(reply, "\n");
            }

            virtual void Reset() noexcept override
            {
                ValueFlagBase::Reset();
                cword = 0;
                syntax.clear();
                reply.clear();
            }
    };


    /** Base class for positional options
     */
    class PositionalBase : public NamedBase
    {
        protected:
            bool ready;

        public:
            PositionalBase(const std::string &name_, const std::string &help_, Options options_ = {}) : NamedBase(name_, help_, options_), ready(true) {}
            virtual ~PositionalBase() {}

            bool Ready()
            {
                return ready;
            }

            virtual void ParseValue(const std::string &value_) = 0;

            virtual void Reset() noexcept override
            {
                matched = false;
                ready = true;
#ifdef ARGS_NOEXCEPT
                error = Error::None;
                errorMsg.clear();
#endif
            }

            virtual PositionalBase *GetNextPositional() override
            {
                return Ready() ? this : nullptr;
            }

            virtual bool HasPositional() const override
            {
                return true;
            }

            virtual std::vector<std::string> GetProgramLine(const HelpParams &params) const override
            {
                return { IsRequired() ? params.proglineRequiredOpen + Name() + params.proglineRequiredClose
                                      : params.proglineNonrequiredOpen + Name() + params.proglineNonrequiredClose };
            }

            virtual void Validate(const std::string &, const std::string &) const override
            {
                if (IsRequired() && !Matched())
                {
                    std::ostringstream problem;
                    problem << "Option '" << Name() << "' is required";
#ifdef ARGS_NOEXCEPT
                    error = Error::Required;
                    errorMsg = problem.str();
#else
                    throw RequiredError(problem.str());
#endif
                }
            }
    };

    /** Class for all kinds of validating groups, including ArgumentParser
     */
    class Group : public Base
    {
        private:
            std::vector<Base*> children;
            std::function<bool(const Group &)> validator;

        public:
            /** Default validators
             */
            struct Validators
            {
                static bool Xor(const Group &group)
                {
                    return group.MatchedChildren() == 1;
                }

                static bool AtLeastOne(const Group &group)
                {
                    return group.MatchedChildren() >= 1;
                }

                static bool AtMostOne(const Group &group)
                {
                    return group.MatchedChildren() <= 1;
                }

                static bool All(const Group &group)
                {
                    return group.Children().size() == group.MatchedChildren();
                }

                static bool AllOrNone(const Group &group)
                {
                    return (All(group) || None(group));
                }

                static bool AllChildGroups(const Group &group)
                {
                    return std::none_of(std::begin(group.Children()), std::end(group.Children()), [](const Base* child) -> bool {
                            return child->IsGroup() && !child->Matched();
                            });
                }

                static bool DontCare(const Group &)
                {
                    return true;
                }

                static bool CareTooMuch(const Group &)
                {
                    return false;
                }

                static bool None(const Group &group)
                {
                    return group.MatchedChildren() == 0;
                }
            };
            /// If help is empty, this group will not be printed in help output
            Group(const std::string &help_ = std::string(), const std::function<bool(const Group &)> &validator_ = Validators::DontCare, Options options_ = {}) : Base(help_, options_), validator(validator_) {}
            /// If help is empty, this group will not be printed in help output
            Group(Group &group_, const std::string &help_ = std::string(), const std::function<bool(const Group &)> &validator_ = Validators::DontCare, Options options_ = {}) : Base(help_, options_), validator(validator_)
            {
                group_.Add(*this);
            }
            virtual ~Group() {}

            /** Append a child to this Group.
             */
            void Add(Base &child)
            {
                children.emplace_back(&child);
            }

            /** Get all this group's children
             */
            const std::vector<Base *> &Children() const
            {
                return children;
            }

            /** Return the first FlagBase that matches flag, or nullptr
             *
             * \param flag The flag with prefixes stripped
             * \return the first matching FlagBase pointer, or nullptr if there is no match
             */
            virtual FlagBase *Match(const EitherFlag &flag) override
            {
                for (Base *child: Children())
                {
                    if (FlagBase *match = child->Match(flag))
                    {
                        return match;
                    }
                }
                return nullptr;
            }

            virtual std::vector<FlagBase*> GetAllFlags() override
            {
                std::vector<FlagBase*> res;
                for (Base *child: Children())
                {
                    auto childRes = child->GetAllFlags();
                    res.insert(res.end(), childRes.begin(), childRes.end());
                }
                return res;
            }

            virtual void Validate(const std::string &shortPrefix, const std::string &longPrefix) const override
            {
                for (Base *child: Children())
                {
                    child->Validate(shortPrefix, longPrefix);
                }
            }

            /** Get the next ready positional, or nullptr if there is none
             *
             * \return the first ready PositionalBase pointer, or nullptr if there is no match
             */
            virtual PositionalBase *GetNextPositional() override
            {
                for (Base *child: Children())
                {
                    if (auto next = child->GetNextPositional())
                    {
                        return next;
                    }
                }
                return nullptr;
            }

            /** Get whether this has any FlagBase children
             *
             * \return Whether or not there are any FlagBase children
             */
            virtual bool HasFlag() const override
            {
                return std::any_of(Children().begin(), Children().end(), [](Base *child) { return child->HasFlag(); });
            }

            /** Get whether this has any PositionalBase children
             *
             * \return Whether or not there are any PositionalBase children
             */
            virtual bool HasPositional() const override
            {
                return std::any_of(Children().begin(), Children().end(), [](Base *child) { return child->HasPositional(); });
            }

            /** Get whether this has any Command children
             *
             * \return Whether or not there are any Command children
             */
            virtual bool HasCommand() const override
            {
                return std::any_of(Children().begin(), Children().end(), [](Base *child) { return child->HasCommand(); });
            }

            /** Count the number of matched children this group has
             */
            std::vector<Base *>::size_type MatchedChildren() const
            {
                // Cast to avoid warnings from -Wsign-conversion
                return static_cast<std::vector<Base *>::size_type>(
                        std::count_if(std::begin(Children()), std::end(Children()), [](const Base *child){return child->Matched();}));
            }

            /** Whether or not this group matches validation
             */
            virtual bool Matched() const noexcept override
            {
                return validator(*this);
            }

            /** Get validation
             */
            bool Get() const
            {
                return Matched();
            }

            /** Get all the child descriptions for help generation
             */
            virtual std::vector<std::tuple<std::string, std::string, unsigned>> GetDescription(const HelpParams &params, const unsigned int indent) const override
            {
                std::vector<std::tuple<std::string, std::string, unsigned int>> descriptions;

                // Push that group description on the back if not empty
                unsigned addindent = 0;
                if (!help.empty())
                {
                    descriptions.emplace_back(help, "", indent);
                    addindent = 1;
                }

                for (Base *child: Children())
                {
                    if ((child->GetOptions() & Options::HiddenFromDescription) != Options::None)
                    {
                        continue;
                    }

                    auto groupDescriptions = child->GetDescription(params, indent + addindent);
                    descriptions.insert(
                        std::end(descriptions),
                        std::make_move_iterator(std::begin(groupDescriptions)),
                        std::make_move_iterator(std::end(groupDescriptions)));
                }
                return descriptions;
            }

            /** Get the names of positional parameters
             */
            virtual std::vector<std::string> GetProgramLine(const HelpParams &params) const override
            {
                std::vector <std::string> names;
                for (Base *child: Children())
                {
                    if ((child->GetOptions() & Options::HiddenFromUsage) != Options::None)
                    {
                        continue;
                    }

                    auto groupNames = child->GetProgramLine(params);
                    names.insert(
                        std::end(names),
                        std::make_move_iterator(std::begin(groupNames)),
                        std::make_move_iterator(std::end(groupNames)));
                }
                return names;
            }

            virtual std::vector<Command*> GetCommands() override
            {
                std::vector<Command*> res;
                for (const auto &child : Children())
                {
                    auto subparsers = child->GetCommands();
                    res.insert(std::end(res), std::begin(subparsers), std::end(subparsers));
                }
                return res;
            }

            virtual bool IsGroup() const override
            {
                return true;
            }

            virtual void Reset() noexcept override
            {
                Base::Reset();

                for (auto &child: Children())
                {
                    child->Reset();
                }
#ifdef ARGS_NOEXCEPT
                error = Error::None;
                errorMsg.clear();
#endif
            }

#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            virtual Error GetError() const override
            {
                if (error != Error::None)
                {
                    return error;
                }

                auto it = std::find_if(Children().begin(), Children().end(), [](const Base *child){return child->GetError() != Error::None;});
                if (it == Children().end())
                {
                    return Error::None;
                } else
                {
                    return (*it)->GetError();
                }
            }

            /// Only for ARGS_NOEXCEPT
            virtual std::string GetErrorMsg() const override
            {
                if (error != Error::None)
                {
                    return errorMsg;
                }

                auto it = std::find_if(Children().begin(), Children().end(), [](const Base *child){return child->GetError() != Error::None;});
                if (it == Children().end())
                {
                    return "";
                } else
                {
                    return (*it)->GetErrorMsg();
                }
            }
#endif

    };

    /** Class for using global options in ArgumentParser.
     */
    class GlobalOptions : public Group
    {
        public:
            GlobalOptions(Group &base, Base &options_) : Group(base, {}, Group::Validators::DontCare, Options::Global)
            {
                Add(options_);
            }
    };

    /** Utility class for building subparsers with coroutines/callbacks.
     *
     * Brief example:
     * \code
     * Command command(argumentParser, "command", "my command", [](args::Subparser &s)
     * {
     *      // your command flags/positionals
     *      s.Parse(); //required
     *      //your command code
     * });
     * \endcode
     *
     * For ARGS_NOEXCEPT mode don't forget to check `s.GetError()` after `s.Parse()`
     * and return if it isn't equals to args::Error::None.
     *
     * \sa Command
     */
    class Subparser : public Group
    {
        private:
            std::vector<std::string> args;
            std::vector<std::string> kicked;
            ArgumentParser *parser = nullptr;
            const HelpParams &helpParams;
            const Command &command;
            bool isParsed = false;

        public:
            Subparser(std::vector<std::string> args_, ArgumentParser &parser_, const Command &command_, const HelpParams &helpParams_)
                : Group({}, Validators::AllChildGroups), args(std::move(args_)), parser(&parser_), helpParams(helpParams_), command(command_)
            {
            }

            Subparser(const Command &command_, const HelpParams &helpParams_) : Group({}, Validators::AllChildGroups), helpParams(helpParams_), command(command_)
            {
            }

            Subparser(const Subparser&) = delete;
            Subparser(Subparser&&) = delete;
            Subparser &operator = (const Subparser&) = delete;
            Subparser &operator = (Subparser&&) = delete;

            const Command &GetCommand()
            {
                return command;
            }

            /** (INTERNAL) Determines whether Parse was called or not.
             */
            bool IsParsed() const
            {
                return isParsed;
            }

            /** Continue parsing arguments for new command.
             */
            void Parse();

            /** Returns a vector of kicked out arguments.
             *
             * \sa Base::KickOut
             */
            const std::vector<std::string> &KickedOut() const noexcept
            {
                return kicked;
            }
    };

    /** Main class for building subparsers.
     *
     * /sa Subparser
     */
    class Command : public Group
    {
        private:
            friend class Subparser;

            std::string name;
            std::string help;
            std::string description;
            std::string epilog;
            std::string proglinePostfix;

            std::function<void(Subparser&)> parserCoroutine;
            bool commandIsRequired = true;
            Command *selectedCommand = nullptr;

            mutable std::vector<std::tuple<std::string, std::string, unsigned>> subparserDescription;
            mutable std::vector<std::string> subparserProgramLine;
            mutable bool subparserHasFlag = false;
            mutable bool subparserHasPositional = false;
            mutable bool subparserHasCommand = false;
#ifdef ARGS_NOEXCEPT
            mutable Error subparserError = Error::None;
#endif
            mutable Subparser *subparser = nullptr;

        protected:

            class RaiiSubparser
            {
                public:
                    RaiiSubparser(ArgumentParser &parser_, std::vector<std::string> args_);
                    RaiiSubparser(const Command &command_, const HelpParams &params_);

                    ~RaiiSubparser()
                    {
                        command.subparser = oldSubparser;
                    }

                    Subparser &Parser()
                    {
                        return parser;
                    }

                private:
                    const Command &command;
                    Subparser parser;
                    Subparser *oldSubparser;
            };

            Command() = default;

            std::function<void(Subparser&)> &GetCoroutine()
            {
                return selectedCommand != nullptr ? selectedCommand->GetCoroutine() : parserCoroutine;
            }

            Command &SelectedCommand()
            {
                Command *res = this;
                while (res->selectedCommand != nullptr)
                {
                    res = res->selectedCommand;
                }

                return *res;
            }

            const Command &SelectedCommand() const
            {
                const Command *res = this;
                while (res->selectedCommand != nullptr)
                {
                    res = res->selectedCommand;
                }

                return *res;
            }

            void UpdateSubparserHelp(const HelpParams &params) const
            {
                if (parserCoroutine)
                {
                    RaiiSubparser coro(*this, params);
#ifndef ARGS_NOEXCEPT
                    try
                    {
                        parserCoroutine(coro.Parser());
                    }
                    catch (args::SubparserError&)
                    {
                    }
#else
                    parserCoroutine(coro.Parser());
#endif
                }
            }

        public:
            Command(Group &base_, std::string name_, std::string help_, std::function<void(Subparser&)> coroutine_ = {})
                : name(std::move(name_)), help(std::move(help_)), parserCoroutine(std::move(coroutine_))
            {
                base_.Add(*this);
            }

            /** The description that appears on the prog line after options
             */
            const std::string &ProglinePostfix() const
            { return proglinePostfix; }

            /** The description that appears on the prog line after options
             */
            void ProglinePostfix(const std::string &proglinePostfix_)
            { this->proglinePostfix = proglinePostfix_; }

            /** The description that appears above options
             */
            const std::string &Description() const
            { return description; }
            /** The description that appears above options
             */

            void Description(const std::string &description_)
            { this->description = description_; }

            /** The description that appears below options
             */
            const std::string &Epilog() const
            { return epilog; }

            /** The description that appears below options
             */
            void Epilog(const std::string &epilog_)
            { this->epilog = epilog_; }

            /** The name of command
             */
            const std::string &Name() const
            { return name; }

            /** The description of command
             */
            const std::string &Help() const
            { return help; }

            /** If value is true, parser will fail if no command was parsed.
             *
             * Default: true.
             */
            void RequireCommand(bool value)
            { commandIsRequired = value; }

            virtual bool IsGroup() const override
            { return false; }

            virtual bool Matched() const noexcept override
            { return Base::Matched(); }

            operator bool() const noexcept
            { return Matched(); }

            void Match() noexcept
            { matched = true; }

            void SelectCommand(Command *c) noexcept
            {
                selectedCommand = c;

                if (c != nullptr)
                {
                    c->Match();
                }
            }

            virtual FlagBase *Match(const EitherFlag &flag) override
            {
                if (selectedCommand != nullptr)
                {
                    if (auto *res = selectedCommand->Match(flag))
                    {
                        return res;
                    }

                    for (auto *child: Children())
                    {
                        if ((child->GetOptions() & Options::Global) != Options::None)
                        {
                            if (auto *res = child->Match(flag))
                            {
                                return res;
                            }
                        }
                    }

                    return nullptr;
                }

                if (subparser != nullptr)
                {
                    return subparser->Match(flag);
                }

                return Matched() ? Group::Match(flag) : nullptr;
            }

            virtual std::vector<FlagBase*> GetAllFlags() override
            {
                std::vector<FlagBase*> res;

                if (!Matched())
                {
                    return res;
                }

                for (auto *child: Children())
                {
                    if (selectedCommand == nullptr || (child->GetOptions() & Options::Global) != Options::None)
                    {
                        auto childFlags = child->GetAllFlags();
                        res.insert(res.end(), childFlags.begin(), childFlags.end());
                    }
                }

                if (selectedCommand != nullptr)
                {
                    auto childFlags = selectedCommand->GetAllFlags();
                    res.insert(res.end(), childFlags.begin(), childFlags.end());
                }

                if (subparser != nullptr)
                {
                    auto childFlags = subparser->GetAllFlags();
                    res.insert(res.end(), childFlags.begin(), childFlags.end());
                }

                return res;
            }

            virtual PositionalBase *GetNextPositional() override
            {
                if (selectedCommand != nullptr)
                {
                    if (auto *res = selectedCommand->GetNextPositional())
                    {
                        return res;
                    }

                    for (auto *child: Children())
                    {
                        if ((child->GetOptions() & Options::Global) != Options::None)
                        {
                            if (auto *res = child->GetNextPositional())
                            {
                                return res;
                            }
                        }
                    }

                    return nullptr;
                }

                if (subparser != nullptr)
                {
                    return subparser->GetNextPositional();
                }

                return Matched() ? Group::GetNextPositional() : nullptr;
            }

            virtual bool HasFlag() const override
            {
                return subparserHasFlag || Group::HasFlag();
            }

            virtual bool HasPositional() const override
            {
                return subparserHasPositional || Group::HasPositional();
            }

            virtual bool HasCommand() const override
            {
                return true;
            }

            std::vector<std::string> GetCommandProgramLine(const HelpParams &params) const
            {
                UpdateSubparserHelp(params);

                auto res = Group::GetProgramLine(params);
                res.insert(res.end(), subparserProgramLine.begin(), subparserProgramLine.end());

                if (!params.proglineCommand.empty() && (Group::HasCommand() || subparserHasCommand))
                {
                    res.insert(res.begin(), commandIsRequired ? params.proglineCommand : "[" + params.proglineCommand + "]");
                }

                if (!Name().empty())
                {
                    res.insert(res.begin(), Name());
                }

                if ((subparserHasFlag || Group::HasFlag()) && params.showProglineOptions && !params.proglineShowFlags)
                {
                    res.push_back(params.proglineOptions);
                }

                if (!ProglinePostfix().empty())
                {
                    std::string line;
                    for (auto c : ProglinePostfix())
                    {
                        if (std::isspace(static_cast<unsigned char>(c)))
                        {
                            if (!line.empty())
                            {
                                res.push_back(line);
                                line.clear();
                            }

                            if (c == '\n')
                            {
                                res.push_back("\n");
                            }
                        }
                        else
                        {
                            line += c;
                        }
                    }

                    if (!line.empty())
                    {
                        res.push_back(line);
                    }
                }

                return res;
            }

            virtual std::vector<std::string> GetProgramLine(const HelpParams &params) const override
            {
                if (!Matched())
                {
                    return {};
                }

                return GetCommandProgramLine(params);
            }

            virtual std::vector<Command*> GetCommands() override
            {
                if (selectedCommand != nullptr)
                {
                    return selectedCommand->GetCommands();
                }

                if (Matched())
                {
                    return Group::GetCommands();
                }

                return { this };
            }

            virtual std::vector<std::tuple<std::string, std::string, unsigned>> GetDescription(const HelpParams &params, const unsigned int indent) const override
            {
                std::vector<std::tuple<std::string, std::string, unsigned>> descriptions;
                unsigned addindent = 0;

                UpdateSubparserHelp(params);

                if (!Matched())
                {
                    if (params.showCommandFullHelp)
                    {
                        std::ostringstream s;
                        bool empty = true;
                        for (const auto &progline: GetCommandProgramLine(params))
                        {
                            if (!empty)
                            {
                                s << ' ';
                            }
                            else
                            {
                                empty = false;
                            }

                            s << progline;
                        }

                        descriptions.emplace_back(s.str(), "", indent);
                    }
                    else
                    {
                        descriptions.emplace_back(Name(), help, indent);
                    }

                    if (!params.showCommandChildren && !params.showCommandFullHelp)
                    {
                        return descriptions;
                    }

                    addindent = 1;
                }

                if (params.showCommandFullHelp && !Matched())
                {
                    descriptions.emplace_back("", "", indent + addindent);
                    descriptions.emplace_back(Description().empty() ? Help() : Description(), "", indent + addindent);
                    descriptions.emplace_back("", "", indent + addindent);
                }

                for (Base *child: Children())
                {
                    if ((child->GetOptions() & Options::HiddenFromDescription) != Options::None)
                    {
                        continue;
                    }

                    auto groupDescriptions = child->GetDescription(params, indent + addindent);
                    descriptions.insert(
                                        std::end(descriptions),
                                        std::make_move_iterator(std::begin(groupDescriptions)),
                                        std::make_move_iterator(std::end(groupDescriptions)));
                }

                for (auto childDescription: subparserDescription)
                {
                    std::get<2>(childDescription) += indent + addindent;
                    descriptions.push_back(std::move(childDescription));
                }

                if (params.showCommandFullHelp && !Matched())
                {
                    descriptions.emplace_back("", "", indent + addindent);
                    if (!Epilog().empty())
                    {
                        descriptions.emplace_back(Epilog(), "", indent + addindent);
                        descriptions.emplace_back("", "", indent + addindent);
                    }
                }

                return descriptions;
            }

            virtual void Validate(const std::string &shortprefix, const std::string &longprefix) const override
            {
                if (!Matched())
                {
                    return;
                }

                auto onValidationError = [&]
                {
                    std::ostringstream problem;
                    problem << "Group validation failed somewhere!";
#ifdef ARGS_NOEXCEPT
                    error = Error::Validation;
                    errorMsg = problem.str();
#else
                    throw ValidationError(problem.str());
#endif
                };

                for (Base *child: Children())
                {
                    if (child->IsGroup() && !child->Matched())
                    {
                        onValidationError();
                    }

                    child->Validate(shortprefix, longprefix);
                }

                if (subparser != nullptr)
                {
                    subparser->Validate(shortprefix, longprefix);
                    if (!subparser->Matched())
                    {
                        onValidationError();
                    }
                }

                if (selectedCommand == nullptr && commandIsRequired && (Group::HasCommand() || subparserHasCommand))
                {
                    std::ostringstream problem;
                    problem << "Command is required";
#ifdef ARGS_NOEXCEPT
                    error = Error::Validation;
                    errorMsg = problem.str();
#else
                    throw ValidationError(problem.str());
#endif
                }
            }

            virtual void Reset() noexcept override
            {
                Group::Reset();
                selectedCommand = nullptr;
                subparserProgramLine.clear();
                subparserDescription.clear();
                subparserHasFlag = false;
                subparserHasPositional = false;
                subparserHasCommand = false;
#ifdef ARGS_NOEXCEPT
                subparserError = Error::None;
#endif
            }

#ifdef ARGS_NOEXCEPT
            /// Only for ARGS_NOEXCEPT
            virtual Error GetError() const override
            {
                if (!Matched())
                {
                    return Error::None;
                }

                if (error != Error::None)
                {
                    return error;
                }

                if (subparserError != Error::None)
                {
                    return subparserError;
                }

                return Group::GetError();
            }
#endif
    };

    /** The main user facing command line argument parser class
     */
    class ArgumentParser : public Command
    {
        friend class Subparser;

        private:
            std::string longprefix;
            std::string shortprefix;

            std::string longseparator;

            std::string terminator;

            bool allowJoinedShortValue = true;
            bool allowJoinedLongValue = true;
            bool allowSeparateShortValue = true;
            bool allowSeparateLongValue = true;

            CompletionFlag *completion = nullptr;
            bool readCompletion = false;

        protected:
            enum class OptionType
            {
                LongFlag,
                ShortFlag,
                Positional
            };

            OptionType ParseOption(const std::string &s, bool allowEmpty = false)
            {
                if (s.find(longprefix) == 0 && (allowEmpty || s.length() > longprefix.length()))
                {
                    return OptionType::LongFlag;
                }

                if (s.find(shortprefix) == 0 && (allowEmpty || s.length() > shortprefix.length()))
                {
                    return OptionType::ShortFlag;
                }

                return OptionType::Positional;
            }

            template <typename It>
            bool Complete(FlagBase &flag, It it, It end)
            {
                auto nextIt = it;
                if (!readCompletion || (++nextIt != end))
                {
                    return false;
                }

                const auto &chunk = *it;
                for (auto &choice : flag.HelpChoices(helpParams))
                {
                    AddCompletionReply(chunk, choice);
                }

#ifndef ARGS_NOEXCEPT
                throw Completion(completion->Get());
#else
                return true;
#endif
            }

            /** (INTERNAL) Parse flag's values
             *
             * \param arg The string to display in error message as a flag name
             * \param[in, out] it The iterator to first value. It will point to the last value
             * \param end The end iterator
             * \param joinedArg Joined value (e.g. bar in --foo=bar)
             * \param canDiscardJoined If true joined value can be parsed as flag not as a value (as in -abcd)
             * \param[out] values The vector to store parsed arg's values
             */
            template <typename It>
            std::string ParseArgsValues(FlagBase &flag, const std::string &arg, It &it, It end,
                                        const bool allowSeparate, const bool allowJoined,
                                        const bool hasJoined, const std::string &joinedArg,
                                        const bool canDiscardJoined, std::vector<std::string> &values)
            {
                values.clear();

                Nargs nargs = flag.NumberOfArguments();

                if (hasJoined && !allowJoined && nargs.min != 0)
                {
                    return "Flag '" + arg + "' was passed a joined argument, but these are disallowed";
                }

                if (hasJoined)
                {
                    if (!canDiscardJoined || nargs.max != 0)
                    {
                        values.push_back(joinedArg);
                    }
                } else if (!allowSeparate)
                {
                    if (nargs.min != 0)
                    {
                        return "Flag '" + arg + "' was passed a separate argument, but these are disallowed";
                    }
                } else
                {
                    auto valueIt = it;
                    ++valueIt;

                    while (valueIt != end &&
                           values.size() < nargs.max &&
                           (values.size() < nargs.min || ParseOption(*valueIt) == OptionType::Positional))
                    {
                        if (Complete(flag, valueIt, end))
                        {
                            it = end;
                            return "";
                        }

                        values.push_back(*valueIt);
                        ++it;
                        ++valueIt;
                    }
                }

                if (values.size() > nargs.max)
                {
                    return "Passed an argument into a non-argument flag: " + arg;
                } else if (values.size() < nargs.min)
                {
                    if (nargs.min == 1 && nargs.max == 1)
                    {
                        return "Flag '" + arg + "' requires an argument but received none";
                    } else if (nargs.min == 1)
                    {
                        return "Flag '" + arg + "' requires at least one argument but received none";
                    } else if (nargs.min != nargs.max)
                    {
                        return "Flag '" + arg + "' requires at least " + std::to_string(nargs.min) +
                               " arguments but received " + std::to_string(values.size());
                    } else
                    {
                        return "Flag '" + arg + "' requires " + std::to_string(nargs.min) +
                               " arguments but received " + std::to_string(values.size());
                    }
                }

                return {};
            }

            template <typename It>
            bool ParseLong(It &it, It end)
            {
                const auto &chunk = *it;
                const auto argchunk = chunk.substr(longprefix.size());
                // Try to separate it, in case of a separator:
                const auto separator = longseparator.empty() ? argchunk.npos : argchunk.find(longseparator);
                // If the separator is in the argument, separate it.
                const auto arg = (separator != argchunk.npos ?
                    std::string(argchunk, 0, separator)
                    : argchunk);
                const auto joined = (separator != argchunk.npos ?
                    argchunk.substr(separator + longseparator.size())
                    : std::string());

                if (auto flag = Match(arg))
                {
                    std::vector<std::string> values;
                    const std::string errorMessage = ParseArgsValues(*flag, arg, it, end, allowSeparateLongValue, allowJoinedLongValue,
                                                                     separator != argchunk.npos, joined, false, values);
                    if (!errorMessage.empty())
                    {
#ifndef ARGS_NOEXCEPT
                        throw ParseError(errorMessage);
#else
                        error = Error::Parse;
                        errorMsg = errorMessage;
                        return false;
#endif
                    }

                    if (!readCompletion)
                    {
                        flag->ParseValue(values);
                    }

                    if (flag->KickOut())
                    {
                        ++it;
                        return false;
                    }
                } else
                {
                    const std::string errorMessage("Flag could not be matched: " + arg);
#ifndef ARGS_NOEXCEPT
                    throw ParseError(errorMessage);
#else
                    error = Error::Parse;
                    errorMsg = errorMessage;
                    return false;
#endif
                }

                return true;
            }

            template <typename It>
            bool ParseShort(It &it, It end)
            {
                const auto &chunk = *it;
                const auto argchunk = chunk.substr(shortprefix.size());
                for (auto argit = std::begin(argchunk); argit != std::end(argchunk); ++argit)
                {
                    const auto arg = *argit;

                    if (auto flag = Match(arg))
                    {
                        const std::string value(argit + 1, std::end(argchunk));
                        std::vector<std::string> values;
                        const std::string errorMessage = ParseArgsValues(*flag, std::string(1, arg), it, end,
                                                                         allowSeparateShortValue, allowJoinedShortValue,
                                                                         !value.empty(), value, !value.empty(), values);

                        if (!errorMessage.empty())
                        {
#ifndef ARGS_NOEXCEPT
                            throw ParseError(errorMessage);
#else
                            error = Error::Parse;
                            errorMsg = errorMessage;
                            return false;
#endif
                        }

                        if (!readCompletion)
                        {
                            flag->ParseValue(values);
                        }

                        if (flag->KickOut())
                        {
                            ++it;
                            return false;
                        }

                        if (!values.empty())
                        {
                            break;
                        }
                    } else
                    {
                        const std::string errorMessage("Flag could not be matched: '" + std::string(1, arg) + "'");
#ifndef ARGS_NOEXCEPT
                        throw ParseError(errorMessage);
#else
                        error = Error::Parse;
                        errorMsg = errorMessage;
                        return false;
#endif
                    }
                }

                return true;
            }

            bool AddCompletionReply(const std::string &cur, const std::string &choice)
            {
                if (cur.empty() || choice.find(cur) == 0)
                {
                    if (completion->syntax == "bash" && ParseOption(choice) == OptionType::LongFlag && choice.find(longseparator) != std::string::npos)
                    {
                        completion->reply.push_back(choice.substr(choice.find(longseparator) + 1));
                    } else
                    {
                        completion->reply.push_back(choice);
                    }
                    return true;
                }

                return false;
            }

            template <typename It>
            bool Complete(It it, It end)
            {
                auto nextIt = it;
                if (!readCompletion || (++nextIt != end))
                {
                    return false;
                }

                const auto &chunk = *it;
                auto pos = GetNextPositional();
                std::vector<Command *> commands = GetCommands();
                const auto optionType = ParseOption(chunk, true);

                if (!commands.empty() && (chunk.empty() || optionType == OptionType::Positional))
                {
                    for (auto &cmd : commands)
                    {
                        if ((cmd->GetOptions() & Options::HiddenFromCompletion) == Options::None)
                        {
                            AddCompletionReply(chunk, cmd->Name());
                        }
                    }
                } else
                {
                    bool hasPositionalCompletion = true;

                    if (!commands.empty())
                    {
                        for (auto &cmd : commands)
                        {
                            if ((cmd->GetOptions() & Options::HiddenFromCompletion) == Options::None)
                            {
                                AddCompletionReply(chunk, cmd->Name());
                            }
                        }
                    } else if (pos)
                    {
                        if ((pos->GetOptions() & Options::HiddenFromCompletion) == Options::None)
                        {
                            auto choices = pos->HelpChoices(helpParams);
                            hasPositionalCompletion = !choices.empty() || optionType != OptionType::Positional;
                            for (auto &choice : choices)
                            {
                                AddCompletionReply(chunk, choice);
                            }
                        }
                    }

                    if (hasPositionalCompletion)
                    {
                        auto flags = GetAllFlags();
                        for (auto flag : flags)
                        {
                            if ((flag->GetOptions() & Options::HiddenFromCompletion) != Options::None)
                            {
                                continue;
                            }

                            auto &matcher = flag->GetMatcher();
                            if (!AddCompletionReply(chunk, matcher.GetShortOrAny().str(shortprefix, longprefix)))
                            {
                                for (auto &flagName : matcher.GetFlagStrings())
                                {
                                    if (AddCompletionReply(chunk, flagName.str(shortprefix, longprefix)))
                                    {
                                        break;
                                    }
                                }
                            }
                        }

                        if (optionType == OptionType::LongFlag && allowJoinedLongValue)
                        {
                            const auto separator = longseparator.empty() ? chunk.npos : chunk.find(longseparator);
                            if (separator != chunk.npos)
                            {
                                std::string arg(chunk, 0, separator);
                                if (auto flag = this->Match(arg.substr(longprefix.size())))
                                {
                                    for (auto &choice : flag->HelpChoices(helpParams))
                                    {
                                        AddCompletionReply(chunk, arg + longseparator + choice);
                                    }
                                }
                            }
                        } else if (optionType == OptionType::ShortFlag && allowJoinedShortValue)
                        {
                            if (chunk.size() > shortprefix.size() + 1)
                            {
                                auto arg = chunk.at(shortprefix.size());
                                //TODO: support -abcVALUE where a and b take no value
                                if (auto flag = this->Match(arg))
                                {
                                    for (auto &choice : flag->HelpChoices(helpParams))
                                    {
                                        AddCompletionReply(chunk, shortprefix + arg + choice);
                                    }
                                }
                            }
                        }
                    }
                }

#ifndef ARGS_NOEXCEPT
                throw Completion(completion->Get());
#else
                return true;
#endif
            }

            template <typename It>
            It Parse(It begin, It end)
            {
                bool terminated = false;
                std::vector<Command *> commands = GetCommands();

                // Check all arg chunks
                for (auto it = begin; it != end; ++it)
                {
                    if (Complete(it, end))
                    {
                        return end;
                    }

                    const auto &chunk = *it;

                    if (!terminated && chunk == terminator)
                    {
                        terminated = true;
                    } else if (!terminated && ParseOption(chunk) == OptionType::LongFlag)
                    {
                        if (!ParseLong(it, end))
                        {
                            return it;
                        }
                    } else if (!terminated && ParseOption(chunk) == OptionType::ShortFlag)
                    {
                        if (!ParseShort(it, end))
                        {
                            return it;
                        }
                    } else if (!terminated && !commands.empty())
                    {
                        auto itCommand = std::find_if(commands.begin(), commands.end(), [&chunk](Command *c) { return c->Name() == chunk; });
                        if (itCommand == commands.end())
                        {
                            const std::string errorMessage("Unknown command: " + chunk);
#ifndef ARGS_NOEXCEPT
                            throw ParseError(errorMessage);
#else
                            error = Error::Parse;
                            errorMsg = errorMessage;
                            return it;
#endif
                        }

                        SelectCommand(*itCommand);

                        if (const auto &coroutine = GetCoroutine())
                        {
                            ++it;
                            RaiiSubparser coro(*this, std::vector<std::string>(it, end));
                            coroutine(coro.Parser());
#ifdef ARGS_NOEXCEPT
                            error = GetError();
                            if (error != Error::None)
                            {
                                return end;
                            }

                            if (!coro.Parser().IsParsed())
                            {
                                error = Error::Usage;
                                return end;
                            }
#else
                            if (!coro.Parser().IsParsed())
                            {
                                throw UsageError("Subparser::Parse was not called");
                            }
#endif

                            break;
                        }

                        commands = GetCommands();
                    } else
                    {
                        auto pos = GetNextPositional();
                        if (pos)
                        {
                            pos->ParseValue(chunk);

                            if (pos->KickOut())
                            {
                                return ++it;
                            }
                        } else
                        {
                            const std::string errorMessage("Passed in argument, but no positional arguments were ready to receive it: " + chunk);
#ifndef ARGS_NOEXCEPT
                            throw ParseError(errorMessage);
#else
                            error = Error::Parse;
                            errorMsg = errorMessage;
                            return it;
#endif
                        }
                    }

                    if (!readCompletion && completion != nullptr && completion->Matched())
                    {
#ifdef ARGS_NOEXCEPT
                        error = Error::Completion;
#endif
                        readCompletion = true;
                        ++it;
                        const auto argsLeft = static_cast<size_t>(std::distance(it, end));
                        if (completion->cword == 0 || argsLeft <= 1 || completion->cword >= argsLeft)
                        {
#ifndef ARGS_NOEXCEPT
                            throw Completion("");
#endif
                        }

                        std::vector<std::string> curArgs(++it, end);
                        curArgs.resize(completion->cword);

                        if (completion->syntax == "bash")
                        {
                            // bash tokenizes --flag=value as --flag=value
                            for (size_t idx = 0; idx < curArgs.size(); )
                            {
                                if (idx > 0 && curArgs[idx] == "=")
                                {
                                    curArgs[idx - 1] += "=";
                                    // Avoid warnings from -Wsign-conversion
                                    const auto signedIdx = static_cast<std::ptrdiff_t>(idx);
                                    if (idx + 1 < curArgs.size())
                                    {
                                        curArgs[idx - 1] += curArgs[idx + 1];
                                        curArgs.erase(curArgs.begin() + signedIdx, curArgs.begin() + signedIdx + 2);
                                    } else
                                    {
                                        curArgs.erase(curArgs.begin() + signedIdx);
                                    }
                                } else
                                {
                                    ++idx;
                                }
                            }

                        }
#ifndef ARGS_NOEXCEPT
                        try
                        {
                            Parse(curArgs.begin(), curArgs.end());
                            throw Completion("");
                        }
                        catch (Completion &)
                        {
                            throw;
                        }
                        catch (args::Error&)
                        {
                            throw Completion("");
                        }
#else
                        return Parse(curArgs.begin(), curArgs.end());
#endif
                    }
                }

                Validate(shortprefix, longprefix);
                return end;
            }

        public:
            HelpParams helpParams;

            ArgumentParser(const std::string &description_, const std::string &epilog_ = std::string())
            {
                Description(description_);
                Epilog(epilog_);
                LongPrefix("--");
                ShortPrefix("-");
                LongSeparator("=");
                Terminator("--");
                SetArgumentSeparations(true, true, true, true);
                matched = true;
            }

            void AddCompletion(CompletionFlag &completionFlag)
            {
                completion = &completionFlag;
                Add(completionFlag);
            }

            /** The program name for help generation
             */
            const std::string &Prog() const
            { return helpParams.programName; }
            /** The program name for help generation
             */
            void Prog(const std::string &prog_)
            { this->helpParams.programName = prog_; }

            /** The prefix for long flags
             */
            const std::string &LongPrefix() const
            { return longprefix; }
            /** The prefix for long flags
             */
            void LongPrefix(const std::string &longprefix_)
            {
                this->longprefix = longprefix_;
                this->helpParams.longPrefix = longprefix_;
            }

            /** The prefix for short flags
             */
            const std::string &ShortPrefix() const
            { return shortprefix; }
            /** The prefix for short flags
             */
            void ShortPrefix(const std::string &shortprefix_)
            {
                this->shortprefix = shortprefix_;
                this->helpParams.shortPrefix = shortprefix_;
            }

            /** The separator for long flags
             */
            const std::string &LongSeparator() const
            { return longseparator; }
            /** The separator for long flags
             */
            void LongSeparator(const std::string &longseparator_)
            {
                if (longseparator_.empty())
                {
                    const std::string errorMessage("longseparator can not be set to empty");
#ifdef ARGS_NOEXCEPT
                    error = Error::Usage;
                    errorMsg = errorMessage;
#else
                    throw UsageError(errorMessage);
#endif
                } else
                {
                    this->longseparator = longseparator_;
                    this->helpParams.longSeparator = allowJoinedLongValue ? longseparator_ : " ";
                }
            }

            /** The terminator that forcibly separates flags from positionals
             */
            const std::string &Terminator() const
            { return terminator; }
            /** The terminator that forcibly separates flags from positionals
             */
            void Terminator(const std::string &terminator_)
            { this->terminator = terminator_; }

            /** Get the current argument separation parameters.
             *
             * See SetArgumentSeparations for details on what each one means.
             */
            void GetArgumentSeparations(
                bool &allowJoinedShortValue_,
                bool &allowJoinedLongValue_,
                bool &allowSeparateShortValue_,
                bool &allowSeparateLongValue_) const
            {
                allowJoinedShortValue_ = this->allowJoinedShortValue;
                allowJoinedLongValue_ = this->allowJoinedLongValue;
                allowSeparateShortValue_ = this->allowSeparateShortValue;
                allowSeparateLongValue_ = this->allowSeparateLongValue;
            }

            /** Change allowed option separation.
             *
             * \param allowJoinedShortValue_ Allow a short flag that accepts an argument to be passed its argument immediately next to it (ie. in the same argv field)
             * \param allowJoinedLongValue_ Allow a long flag that accepts an argument to be passed its argument separated by the longseparator (ie. in the same argv field)
             * \param allowSeparateShortValue_ Allow a short flag that accepts an argument to be passed its argument separated by whitespace (ie. in the next argv field)
             * \param allowSeparateLongValue_ Allow a long flag that accepts an argument to be passed its argument separated by whitespace (ie. in the next argv field)
             */
            void SetArgumentSeparations(
                const bool allowJoinedShortValue_,
                const bool allowJoinedLongValue_,
                const bool allowSeparateShortValue_,
                const bool allowSeparateLongValue_)
            {
                this->allowJoinedShortValue = allowJoinedShortValue_;
                this->allowJoinedLongValue = allowJoinedLongValue_;
                this->allowSeparateShortValue = allowSeparateShortValue_;
                this->allowSeparateLongValue = allowSeparateLongValue_;

                this->helpParams.longSeparator = allowJoinedLongValue ? longseparator : " ";
                this->helpParams.shortSeparator = allowJoinedShortValue ? "" : " ";
            }

            /** Pass the help menu into an ostream
             */
            void Help(std::ostream &help_) const
            {
                auto &command = SelectedCommand();
                const auto &commandDescription = command.Description().empty() ? command.Help() : command.Description();
                const auto description_text = Wrap(commandDescription, helpParams.width - helpParams.descriptionindent);
                const auto epilog_text = Wrap(command.Epilog(), helpParams.width - helpParams.descriptionindent);

                const bool hasoptions = command.HasFlag();
                const bool hasarguments = command.HasPositional();

                std::vector<std::string> prognameline;
                prognameline.push_back(helpParams.usageString);
                prognameline.push_back(Prog());
                auto commandProgLine = command.GetProgramLine(helpParams);
                prognameline.insert(prognameline.end(), commandProgLine.begin(), commandProgLine.end());

                const auto proglines = Wrap(prognameline.begin(), prognameline.end(),
                                            helpParams.width - (helpParams.progindent + helpParams.progtailindent),
                                            helpParams.width - helpParams.progindent);
                auto progit = std::begin(proglines);
                if (progit != std::end(proglines))
                {
                    help_ << std::string(helpParams.progindent, ' ') << *progit << '\n';
                    ++progit;
                }
                for (; progit != std::end(proglines); ++progit)
                {
                    help_ << std::string(helpParams.progtailindent, ' ') << *progit << '\n';
                }

                help_ << '\n';

                if (!description_text.empty())
                {
                    for (const auto &line: description_text)
                    {
                        help_ << std::string(helpParams.descriptionindent, ' ') << line << "\n";
                    }
                    help_ << "\n";
                }

                bool lastDescriptionIsNewline = false;

                if (!helpParams.optionsString.empty())
                {
                    help_ << std::string(helpParams.progindent, ' ') << helpParams.optionsString << "\n\n";
                }

                for (const auto &desc: command.GetDescription(helpParams, 0))
                {
                    lastDescriptionIsNewline = std::get<0>(desc).empty() && std::get<1>(desc).empty();
                    const auto groupindent = std::get<2>(desc) * helpParams.eachgroupindent;
                    const auto flags = Wrap(std::get<0>(desc), helpParams.width - (helpParams.flagindent + helpParams.helpindent + helpParams.gutter));
                    const auto info = Wrap(std::get<1>(desc), helpParams.width - (helpParams.helpindent + groupindent));

                    std::string::size_type flagssize = 0;
                    for (auto flagsit = std::begin(flags); flagsit != std::end(flags); ++flagsit)
                    {
                        if (flagsit != std::begin(flags))
                        {
                            help_ << '\n';
                        }
                        help_ << std::string(groupindent + helpParams.flagindent, ' ') << *flagsit;
                        flagssize = Glyphs(*flagsit);
                    }

                    auto infoit = std::begin(info);
                    // groupindent is on both sides of this inequality, and therefore can be removed
                    if ((helpParams.flagindent + flagssize + helpParams.gutter) > helpParams.helpindent || infoit == std::end(info) || helpParams.addNewlineBeforeDescription)
                    {
                        help_ << '\n';
                    } else
                    {
                        // groupindent is on both sides of the minus sign, and therefore doesn't actually need to be in here
                        help_ << std::string(helpParams.helpindent - (helpParams.flagindent + flagssize), ' ') << *infoit << '\n';
                        ++infoit;
                    }
                    for (; infoit != std::end(info); ++infoit)
                    {
                        help_ << std::string(groupindent + helpParams.helpindent, ' ') << *infoit << '\n';
                    }
                }
                if (hasoptions && hasarguments && helpParams.showTerminator)
                {
                    lastDescriptionIsNewline = false;
                    for (const auto &item: Wrap(std::string("\"") + terminator + "\" can be used to terminate flag options and force all following arguments to be treated as positional options", helpParams.width - helpParams.flagindent))
                    {
                        help_ << std::string(helpParams.flagindent, ' ') << item << '\n';
                    }
                }

                if (!lastDescriptionIsNewline)
                {
                    help_ << "\n";
                }

                for (const auto &line: epilog_text)
                {
                    help_ << std::string(helpParams.descriptionindent, ' ') << line << "\n";
                }
            }

            /** Generate a help menu as a string.
             *
             * \return the help text as a single string
             */
            std::string Help() const
            {
                std::ostringstream help_;
                Help(help_);
                return help_.str();
            }

            virtual void Reset() noexcept override
            {
                Command::Reset();
                matched = true;
                readCompletion = false;
            }

            /** Parse all arguments.
             *
             * \param begin an iterator to the beginning of the argument list
             * \param end an iterator to the past-the-end element of the argument list
             * \return the iterator after the last parsed value.  Only useful for kick-out
             */
            template <typename It>
            It ParseArgs(It begin, It end)
            {
                // Reset all Matched statuses and errors
                Reset();
#ifdef ARGS_NOEXCEPT
                error = GetError();
                if (error != Error::None)
                {
                    return end;
                }
#endif
                return Parse(begin, end);
            }

            /** Parse all arguments.
             *
             * \param args an iterable of the arguments
             * \return the iterator after the last parsed value.  Only useful for kick-out
             */
            template <typename T>
            auto ParseArgs(const T &args) -> decltype(std::begin(args))
            {
                return ParseArgs(std::begin(args), std::end(args));
            }

            /** Convenience function to parse the CLI from argc and argv
             *
             * Just assigns the program name and vectorizes arguments for passing into ParseArgs()
             *
             * \return whether or not all arguments were parsed.  This works for detecting kick-out, but is generally useless as it can't do anything with it.
             */
            bool ParseCLI(const int argc, const char * const * argv)
            {
                if (Prog().empty())
                {
                    Prog(argv[0]);
                }
                const std::vector<std::string> args(argv + 1, argv + argc);
                return ParseArgs(args) == std::end(args);
            }
            
            template <typename T>
            bool ParseCLI(const T &args)
            {
                return ParseArgs(args) == std::end(args);
            }
    };

    inline Command::RaiiSubparser::RaiiSubparser(ArgumentParser &parser_, std::vector<std::string> args_)
        : command(parser_.SelectedCommand()), parser(std::move(args_), parser_, command, parser_.helpParams), oldSubparser(command.subparser)
    {
        command.subparser = &parser;
    }

    inline Command::RaiiSubparser::RaiiSubparser(const Command &command_, const HelpParams &params_): command(command_), parser(command, params_), oldSubparser(command.subparser)
    {
        command.subparser = &parser;
    }

    inline void Subparser::Parse()
    {
        isParsed = true;
        Reset();
        command.subparserDescription = GetDescription(helpParams, 0);
        command.subparserHasFlag = HasFlag();
        command.subparserHasPositional = HasPositional();
        command.subparserHasCommand = HasCommand();
        command.subparserProgramLine = GetProgramLine(helpParams);
        if (parser == nullptr)
        {
#ifndef ARGS_NOEXCEPT
            throw args::SubparserError();
#else
            error = Error::Subparser;
            return;
#endif
        }

        auto it = parser->Parse(args.begin(), args.end());
        command.Validate(parser->ShortPrefix(), parser->LongPrefix());
        kicked.assign(it, args.end());

#ifdef ARGS_NOEXCEPT
        command.subparserError = GetError();
#endif
    }

    inline std::ostream &operator<<(std::ostream &os, const ArgumentParser &parser)
    {
        parser.Help(os);
        return os;
    }

    /** Boolean argument matcher
     */
    class Flag : public FlagBase
    {
        public:
            Flag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_): FlagBase(name_, help_, std::move(matcher_), options_)
            {
                group_.Add(*this);
            }

            Flag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const bool extraError_ = false): Flag(group_, name_, help_, std::move(matcher_), extraError_ ? Options::Single : Options::None)
            {
            }

            virtual ~Flag() {}

            /** Get whether this was matched
             */
            bool Get() const
            {
                return Matched();
            }

            virtual Nargs NumberOfArguments() const noexcept override
            {
                return 0;
            }

            virtual void ParseValue(const std::vector<std::string>&) override
            {
            }
    };

    /** Help flag class
     *
     * Works like a regular flag, but throws an instance of Help when it is matched
     */
    class HelpFlag : public Flag
    {
        public:
            HelpFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_ = {}): Flag(group_, name_, help_, std::move(matcher_), options_) {}

            virtual ~HelpFlag() {}

            virtual void ParseValue(const std::vector<std::string> &)
            {
#ifdef ARGS_NOEXCEPT
                    error = Error::Help;
                    errorMsg = Name();
#else
                    throw Help(Name());
#endif
            }

            /** Get whether this was matched
             */
            bool Get() const noexcept
            {
                return Matched();
            }
    };

    /** A flag class that simply counts the number of times it's matched
     */
    class CounterFlag : public Flag
    {
        private:
            const int startcount;
            int count;

        public:
            CounterFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const int startcount_ = 0, Options options_ = {}):
                Flag(group_, name_, help_, std::move(matcher_), options_), startcount(startcount_), count(startcount_) {}

            virtual ~CounterFlag() {}

            virtual FlagBase *Match(const EitherFlag &arg) override
            {
                auto me = FlagBase::Match(arg);
                if (me)
                {
                    ++count;
                }
                return me;
            }

            /** Get the count
             */
            int &Get() noexcept
            {
                return count;
            }

            int &operator *() noexcept {
                return count;
            }
            
            const int &operator *() const noexcept {
                return count;
            }

            virtual void Reset() noexcept override
            {
                FlagBase::Reset();
                count = startcount;
            }
    };

    /** A flag class that calls a function when it's matched
     */
    class ActionFlag : public FlagBase
    {
        private:
            std::function<void(const std::vector<std::string> &)> action;
            Nargs nargs;

        public:
            ActionFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Nargs nargs_, std::function<void(const std::vector<std::string> &)> action_, Options options_ = {}):
                FlagBase(name_, help_, std::move(matcher_), options_), action(std::move(action_)), nargs(nargs_)
            {
                group_.Add(*this);
            }

            ActionFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, std::function<void(const std::string &)> action_, Options options_ = {}):
                FlagBase(name_, help_, std::move(matcher_), options_), nargs(1)
            {
                group_.Add(*this);
                action = [action_](const std::vector<std::string> &a) { return action_(a.at(0)); };
            }

            ActionFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, std::function<void()> action_, Options options_ = {}):
                FlagBase(name_, help_, std::move(matcher_), options_), nargs(0)
            {
                group_.Add(*this);
                action = [action_](const std::vector<std::string> &) { return action_(); };
            }

            virtual Nargs NumberOfArguments() const noexcept override
            { return nargs; }

            virtual void ParseValue(const std::vector<std::string> &value) override
            { action(value); }
    };

    /** A default Reader class for argument classes
     *
     * If destination type is assignable to std::string it uses an assignment to std::string.
     * Otherwise ValueReader simply uses a std::istringstream to read into the destination type, and
     * raises a ParseError if there are any characters left.
     */
    struct ValueReader
    {
        template <typename T>
        typename std::enable_if<!std::is_assignable<T, std::string>::value, bool>::type
        operator ()(const std::string &name, const std::string &value, T &destination)
        {
            std::istringstream ss(value);
            bool failed = !(ss >> destination);

            if (!failed)
            {
                ss >> std::ws;
            }

            if (ss.rdbuf()->in_avail() > 0 || failed)
            {
#ifdef ARGS_NOEXCEPT
                (void)name;
                return false;
#else
                std::ostringstream problem;
                problem << "Argument '" << name << "' received invalid value type '" << value << "'";
                throw ParseError(problem.str());
#endif
            }
            return true;
        }

        template <typename T>
        typename std::enable_if<std::is_assignable<T, std::string>::value, bool>::type
        operator()(const std::string &, const std::string &value, T &destination)
        {
            destination = value;
            return true;
        }
    };

    /** An argument-accepting flag class
     * 
     * \tparam T the type to extract the argument as
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        typename Reader = ValueReader>
    class ValueFlag : public ValueFlagBase
    {
        protected:
            T value;
            T defaultValue;

            virtual std::string GetDefaultString(const HelpParams&) const override
            {
                return detail::ToString(defaultValue);
            }

        private:
            Reader reader;

        public:

            ValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const T &defaultValue_, Options options_): ValueFlagBase(name_, help_, std::move(matcher_), options_), value(defaultValue_), defaultValue(defaultValue_)
            {
                group_.Add(*this);
            }

            ValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const T &defaultValue_ = T(), const bool extraError_ = false): ValueFlag(group_, name_, help_, std::move(matcher_), defaultValue_, extraError_ ? Options::Single : Options::None)
            {
            }

            ValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_): ValueFlag(group_, name_, help_, std::move(matcher_), T(), options_)
            {
            }

            virtual ~ValueFlag() {}

            virtual void ParseValue(const std::vector<std::string> &values_) override
            {
                const std::string &value_ = values_.at(0);

#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, this->value))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, this->value);
#endif
            }

            virtual void Reset() noexcept override
            {
                ValueFlagBase::Reset();
                value = defaultValue;
            }

            /** Get the value
             */
            T &Get() noexcept
            {
                return value;
            }

            /** Get the value
             */
            T &operator *() noexcept
            {
                return value;
            }

            /** Get the value
             */
            const T &operator *() const noexcept
            {
                return value;
            }

            /** Get the value
             */
            T *operator ->() noexcept
            {
                return &value;
            }

            /** Get the value
             */
            const T *operator ->() const noexcept
            {
                return &value;
            }

            /** Get the default value
             */
            const T &GetDefault() noexcept
            {
                return defaultValue;
            }
    };

    /** An optional argument-accepting flag class
     *
     * \tparam T the type to extract the argument as
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        typename Reader = ValueReader>
    class ImplicitValueFlag : public ValueFlag<T, Reader>
    {
        protected:
            T implicitValue;

        public:

            ImplicitValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const T &implicitValue_, const T &defaultValue_ = T(), Options options_ = {})
                : ValueFlag<T, Reader>(group_, name_, help_, std::move(matcher_), defaultValue_, options_), implicitValue(implicitValue_)
            {
            }

            ImplicitValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const T &defaultValue_ = T(), Options options_ = {})
                : ValueFlag<T, Reader>(group_, name_, help_, std::move(matcher_), defaultValue_, options_), implicitValue(defaultValue_)
            {
            }

            ImplicitValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Options options_)
                : ValueFlag<T, Reader>(group_, name_, help_, std::move(matcher_), {}, options_), implicitValue()
            {
            }

            virtual ~ImplicitValueFlag() {}

            virtual Nargs NumberOfArguments() const noexcept override
            {
                return {0, 1};
            }

            virtual void ParseValue(const std::vector<std::string> &value_) override
            {
                if (value_.empty())
                {
                    this->value = implicitValue;
                } else
                {
                    ValueFlag<T, Reader>::ParseValue(value_);
                }
            }
    };

    /** A variadic arguments accepting flag class
     *
     * \tparam T the type to extract the argument as
     * \tparam List the list type that houses the values
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        template <typename...> class List = detail::vector,
        typename Reader = ValueReader>
    class NargsValueFlag : public FlagBase
    {
        protected:

            List<T> values;
            const List<T> defaultValues;
            Nargs nargs;
            Reader reader;

        public:

            typedef List<T> Container;
            typedef T value_type;
            typedef typename Container::allocator_type allocator_type;
            typedef typename Container::pointer pointer;
            typedef typename Container::const_pointer const_pointer;
            typedef T& reference;
            typedef const T& const_reference;
            typedef typename Container::size_type size_type;
            typedef typename Container::difference_type difference_type;
            typedef typename Container::iterator iterator;
            typedef typename Container::const_iterator const_iterator;
            typedef std::reverse_iterator<iterator> reverse_iterator;
            typedef std::reverse_iterator<const_iterator> const_reverse_iterator;

            NargsValueFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, Nargs nargs_, const List<T> &defaultValues_ = {}, Options options_ = {})
                : FlagBase(name_, help_, std::move(matcher_), options_), values(defaultValues_), defaultValues(defaultValues_),nargs(nargs_)
            {
                group_.Add(*this);
            }

            virtual ~NargsValueFlag() {}

            virtual Nargs NumberOfArguments() const noexcept override
            {
                return nargs;
            }

            virtual void ParseValue(const std::vector<std::string> &values_) override
            {
                values.clear();

                for (const std::string &value : values_)
                {
                    T v;
#ifdef ARGS_NOEXCEPT
                    if (!reader(name, value, v))
                    {
                        error = Error::Parse;
                    }
#else
                    reader(name, value, v);
#endif
                    values.insert(std::end(values), v);
                }
            }

            List<T> &Get() noexcept
            {
                return values;
            }

            /** Get the value
             */
            List<T> &operator *() noexcept
            {
                return values;
            }

            /** Get the values
             */
            const List<T> &operator *() const noexcept
            {
                return values;
            }

            /** Get the values
             */
            List<T> *operator ->() noexcept
            {
                return &values;
            }

            /** Get the values
             */
            const List<T> *operator ->() const noexcept
            {
                return &values;
            }

            iterator begin() noexcept
            {
                return values.begin();
            }

            const_iterator begin() const noexcept
            {
                return values.begin();
            }

            const_iterator cbegin() const noexcept
            {
                return values.cbegin();
            }

            iterator end() noexcept
            {
                return values.end();
            }

            const_iterator end() const noexcept 
            {
                return values.end();
            }

            const_iterator cend() const noexcept
            {
                return values.cend();
            }

            virtual void Reset() noexcept override
            {
                FlagBase::Reset();
                values = defaultValues;
            }

            virtual FlagBase *Match(const EitherFlag &arg) override
            {
                const bool wasMatched = Matched();
                auto me = FlagBase::Match(arg);
                if (me && !wasMatched)
                {
                    values.clear();
                }
                return me;
            }
    };

    /** An argument-accepting flag class that pushes the found values into a list
     * 
     * \tparam T the type to extract the argument as
     * \tparam List the list type that houses the values
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        template <typename...> class List = detail::vector,
        typename Reader = ValueReader>
    class ValueFlagList : public ValueFlagBase
    {
        private:
            using Container = List<T>;
            Container values;
            const Container defaultValues;
            Reader reader;

        public:

            typedef T value_type;
            typedef typename Container::allocator_type allocator_type;
            typedef typename Container::pointer pointer;
            typedef typename Container::const_pointer const_pointer;
            typedef T& reference;
            typedef const T& const_reference;
            typedef typename Container::size_type size_type;
            typedef typename Container::difference_type difference_type;
            typedef typename Container::iterator iterator;
            typedef typename Container::const_iterator const_iterator;
            typedef std::reverse_iterator<iterator> reverse_iterator;
            typedef std::reverse_iterator<const_iterator> const_reverse_iterator;

            ValueFlagList(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const Container &defaultValues_ = Container(), Options options_ = {}):
                ValueFlagBase(name_, help_, std::move(matcher_), options_), values(defaultValues_), defaultValues(defaultValues_)
            {
                group_.Add(*this);
            }

            virtual ~ValueFlagList() {}

            virtual void ParseValue(const std::vector<std::string> &values_) override
            {
                const std::string &value_ = values_.at(0);

                T v;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, v))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, v);
#endif
                values.insert(std::end(values), v);
            }

            /** Get the values
             */
            Container &Get() noexcept
            {
                return values;
            }

            /** Get the value
             */
            Container &operator *() noexcept
            {
                return values;
            }

            /** Get the values
             */
            const Container &operator *() const noexcept
            {
                return values;
            }

            /** Get the values
             */
            Container *operator ->() noexcept
            {
                return &values;
            }

            /** Get the values
             */
            const Container *operator ->() const noexcept
            {
                return &values;
            }

            virtual std::string Name() const override
            {
                return name + std::string("...");
            }

            virtual void Reset() noexcept override
            {
                ValueFlagBase::Reset();
                values = defaultValues;
            }

            virtual FlagBase *Match(const EitherFlag &arg) override
            {
                const bool wasMatched = Matched();
                auto me = FlagBase::Match(arg);
                if (me && !wasMatched)
                {
                    values.clear();
                }
                return me;
            }

            iterator begin() noexcept
            {
                return values.begin();
            }

            const_iterator begin() const noexcept
            {
                return values.begin();
            }

            const_iterator cbegin() const noexcept
            {
                return values.cbegin();
            }

            iterator end() noexcept
            {
                return values.end();
            }

            const_iterator end() const noexcept 
            {
                return values.end();
            }

            const_iterator cend() const noexcept
            {
                return values.cend();
            }
    };

    /** A mapping value flag class
     * 
     * \tparam K the type to extract the argument as
     * \tparam T the type to store the result as
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     * \tparam Map The Map type.  Should operate like std::map or std::unordered_map
     */
    template <
        typename K,
        typename T,
        typename Reader = ValueReader,
        template <typename...> class Map = detail::unordered_map>
    class MapFlag : public ValueFlagBase
    {
        private:
            const Map<K, T> map;
            T value;
            const T defaultValue;
            Reader reader;

        protected:
            virtual std::vector<std::string> GetChoicesStrings(const HelpParams &) const override
            {
                return detail::MapKeysToStrings(map);
            }

        public:

            MapFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const Map<K, T> &map_, const T &defaultValue_, Options options_): ValueFlagBase(name_, help_, std::move(matcher_), options_), map(map_), value(defaultValue_), defaultValue(defaultValue_)
            {
                group_.Add(*this);
            }

            MapFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const Map<K, T> &map_, const T &defaultValue_ = T(), const bool extraError_ = false): MapFlag(group_, name_, help_, std::move(matcher_), map_, defaultValue_, extraError_ ? Options::Single : Options::None)
            {
            }

            MapFlag(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const Map<K, T> &map_, Options options_): MapFlag(group_, name_, help_, std::move(matcher_), map_, T(), options_)
            {
            }

            virtual ~MapFlag() {}

            virtual void ParseValue(const std::vector<std::string> &values_) override
            {
                const std::string &value_ = values_.at(0);

                K key;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, key))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, key);
#endif
                auto it = map.find(key);
                if (it == std::end(map))
                {
                    std::ostringstream problem;
                    problem << "Could not find key '" << key << "' in map for arg '" << name << "'";
#ifdef ARGS_NOEXCEPT
                    error = Error::Map;
                    errorMsg = problem.str();
#else
                    throw MapError(problem.str());
#endif
                } else
                {
                    this->value = it->second;
                }
            }

            /** Get the value
             */
            T &Get() noexcept
            {
                return value;
            }

            /** Get the value
             */
            T &operator *() noexcept
            {
                return value;
            }

            /** Get the value
             */
            const T &operator *() const noexcept
            {
                return value;
            }

            /** Get the value
             */
            T *operator ->() noexcept
            {
                return &value;
            }

            /** Get the value
             */
            const T *operator ->() const noexcept
            {
                return &value;
            }

            virtual void Reset() noexcept override
            {
                ValueFlagBase::Reset();
                value = defaultValue;
            }
    };

    /** A mapping value flag list class
     * 
     * \tparam K the type to extract the argument as
     * \tparam T the type to store the result as
     * \tparam List the list type that houses the values
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     * \tparam Map The Map type.  Should operate like std::map or std::unordered_map
     */
    template <
        typename K,
        typename T,
        template <typename...> class List = detail::vector,
        typename Reader = ValueReader,
        template <typename...> class Map = detail::unordered_map>
    class MapFlagList : public ValueFlagBase
    {
        private:
            using Container = List<T>;
            const Map<K, T> map;
            Container values;
            const Container defaultValues;
            Reader reader;

        protected:
            virtual std::vector<std::string> GetChoicesStrings(const HelpParams &) const override
            {
                return detail::MapKeysToStrings(map);
            }

        public:
            typedef T value_type;
            typedef typename Container::allocator_type allocator_type;
            typedef typename Container::pointer pointer;
            typedef typename Container::const_pointer const_pointer;
            typedef T& reference;
            typedef const T& const_reference;
            typedef typename Container::size_type size_type;
            typedef typename Container::difference_type difference_type;
            typedef typename Container::iterator iterator;
            typedef typename Container::const_iterator const_iterator;
            typedef std::reverse_iterator<iterator> reverse_iterator;
            typedef std::reverse_iterator<const_iterator> const_reverse_iterator;

            MapFlagList(Group &group_, const std::string &name_, const std::string &help_, Matcher &&matcher_, const Map<K, T> &map_, const Container &defaultValues_ = Container()): ValueFlagBase(name_, help_, std::move(matcher_)), map(map_), values(defaultValues_), defaultValues(defaultValues_)
            {
                group_.Add(*this);
            }

            virtual ~MapFlagList() {}

            virtual void ParseValue(const std::vector<std::string> &values_) override
            {
                const std::string &value = values_.at(0);

                K key;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value, key))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value, key);
#endif
                auto it = map.find(key);
                if (it == std::end(map))
                {
                    std::ostringstream problem;
                    problem << "Could not find key '" << key << "' in map for arg '" << name << "'";
#ifdef ARGS_NOEXCEPT
                    error = Error::Map;
                    errorMsg = problem.str();
#else
                    throw MapError(problem.str());
#endif
                } else
                {
                    this->values.emplace_back(it->second);
                }
            }

            /** Get the value
             */
            Container &Get() noexcept
            {
                return values;
            }

            /** Get the value
             */
            Container &operator *() noexcept
            {
                return values;
            }

            /** Get the values
             */
            const Container &operator *() const noexcept
            {
                return values;
            }

            /** Get the values
             */
            Container *operator ->() noexcept
            {
                return &values;
            }

            /** Get the values
             */
            const Container *operator ->() const noexcept
            {
                return &values;
            }

            virtual std::string Name() const override
            {
                return name + std::string("...");
            }

            virtual void Reset() noexcept override
            {
                ValueFlagBase::Reset();
                values = defaultValues;
            }

            virtual FlagBase *Match(const EitherFlag &arg) override
            {
                const bool wasMatched = Matched();
                auto me = FlagBase::Match(arg);
                if (me && !wasMatched)
                {
                    values.clear();
                }
                return me;
            }

            iterator begin() noexcept
            {
                return values.begin();
            }

            const_iterator begin() const noexcept
            {
                return values.begin();
            }

            const_iterator cbegin() const noexcept
            {
                return values.cbegin();
            }

            iterator end() noexcept
            {
                return values.end();
            }

            const_iterator end() const noexcept 
            {
                return values.end();
            }

            const_iterator cend() const noexcept
            {
                return values.cend();
            }
    };

    /** A positional argument class
     *
     * \tparam T the type to extract the argument as
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        typename Reader = ValueReader>
    class Positional : public PositionalBase
    {
        private:
            T value;
            const T defaultValue;
            Reader reader;
        public:
            Positional(Group &group_, const std::string &name_, const std::string &help_, const T &defaultValue_ = T(), Options options_ = {}): PositionalBase(name_, help_, options_), value(defaultValue_), defaultValue(defaultValue_)
            {
                group_.Add(*this);
            }

            Positional(Group &group_, const std::string &name_, const std::string &help_, Options options_): Positional(group_, name_, help_, T(), options_)
            {
            }

            virtual ~Positional() {}

            virtual void ParseValue(const std::string &value_) override
            {
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, this->value))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, this->value);
#endif
                ready = false;
                matched = true;
            }

            /** Get the value
             */
            T &Get() noexcept
            {
                return value;
            }

            /** Get the value
             */
            T &operator *() noexcept
            {
                return value;
            }

            /** Get the value
             */
            const T &operator *() const noexcept
            {
                return value;
            }

            /** Get the value
             */
            T *operator ->() noexcept
            {
                return &value;
            }

            /** Get the value
             */
            const T *operator ->() const noexcept
            {
                return &value;
            }

            virtual void Reset() noexcept override
            {
                PositionalBase::Reset();
                value = defaultValue;
            }
    };

    /** A positional argument class that pushes the found values into a list
     * 
     * \tparam T the type to extract the argument as
     * \tparam List the list type that houses the values
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     */
    template <
        typename T,
        template <typename...> class List = detail::vector,
        typename Reader = ValueReader>
    class PositionalList : public PositionalBase
    {
        private:
            using Container = List<T>;
            Container values;
            const Container defaultValues;
            Reader reader;

        public:
            typedef T value_type;
            typedef typename Container::allocator_type allocator_type;
            typedef typename Container::pointer pointer;
            typedef typename Container::const_pointer const_pointer;
            typedef T& reference;
            typedef const T& const_reference;
            typedef typename Container::size_type size_type;
            typedef typename Container::difference_type difference_type;
            typedef typename Container::iterator iterator;
            typedef typename Container::const_iterator const_iterator;
            typedef std::reverse_iterator<iterator> reverse_iterator;
            typedef std::reverse_iterator<const_iterator> const_reverse_iterator;

            PositionalList(Group &group_, const std::string &name_, const std::string &help_, const Container &defaultValues_ = Container(), Options options_ = {}): PositionalBase(name_, help_, options_), values(defaultValues_), defaultValues(defaultValues_)
            {
                group_.Add(*this);
            }

            PositionalList(Group &group_, const std::string &name_, const std::string &help_, Options options_): PositionalList(group_, name_, help_, {}, options_)
            {
            }

            virtual ~PositionalList() {}

            virtual void ParseValue(const std::string &value_) override
            {
                T v;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, v))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, v);
#endif
                values.insert(std::end(values), v);
                matched = true;
            }

            virtual std::string Name() const override
            {
                return name + std::string("...");
            }

            /** Get the values
             */
            Container &Get() noexcept
            {
                return values;
            }

            /** Get the value
             */
            Container &operator *() noexcept
            {
                return values;
            }

            /** Get the values
             */
            const Container &operator *() const noexcept
            {
                return values;
            }

            /** Get the values
             */
            Container *operator ->() noexcept
            {
                return &values;
            }

            /** Get the values
             */
            const Container *operator ->() const noexcept
            {
                return &values;
            }

            virtual void Reset() noexcept override
            {
                PositionalBase::Reset();
                values = defaultValues;
            }

            virtual PositionalBase *GetNextPositional() override
            {
                const bool wasMatched = Matched();
                auto me = PositionalBase::GetNextPositional();
                if (me && !wasMatched)
                {
                    values.clear();
                }
                return me;
            }

            iterator begin() noexcept
            {
                return values.begin();
            }

            const_iterator begin() const noexcept
            {
                return values.begin();
            }

            const_iterator cbegin() const noexcept
            {
                return values.cbegin();
            }

            iterator end() noexcept
            {
                return values.end();
            }

            const_iterator end() const noexcept 
            {
                return values.end();
            }

            const_iterator cend() const noexcept
            {
                return values.cend();
            }
    };

    /** A positional argument mapping class
     * 
     * \tparam K the type to extract the argument as
     * \tparam T the type to store the result as
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     * \tparam Map The Map type.  Should operate like std::map or std::unordered_map
     */
    template <
        typename K,
        typename T,
        typename Reader = ValueReader,
        template <typename...> class Map = detail::unordered_map>
    class MapPositional : public PositionalBase
    {
        private:
            const Map<K, T> map;
            T value;
            const T defaultValue;
            Reader reader;

        protected:
            virtual std::vector<std::string> GetChoicesStrings(const HelpParams &) const override
            {
                return detail::MapKeysToStrings(map);
            }

        public:

            MapPositional(Group &group_, const std::string &name_, const std::string &help_, const Map<K, T> &map_, const T &defaultValue_ = T(), Options options_ = {}):
                PositionalBase(name_, help_, options_), map(map_), value(defaultValue_), defaultValue(defaultValue_)
            {
                group_.Add(*this);
            }

            virtual ~MapPositional() {}

            virtual void ParseValue(const std::string &value_) override
            {
                K key;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, key))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, key);
#endif
                auto it = map.find(key);
                if (it == std::end(map))
                {
                    std::ostringstream problem;
                    problem << "Could not find key '" << key << "' in map for arg '" << name << "'";
#ifdef ARGS_NOEXCEPT
                    error = Error::Map;
                    errorMsg = problem.str();
#else
                    throw MapError(problem.str());
#endif
                } else
                {
                    this->value = it->second;
                    ready = false;
                    matched = true;
                }
            }

            /** Get the value
             */
            T &Get() noexcept
            {
                return value;
            }

            /** Get the value
             */
            T &operator *() noexcept
            {
                return value;
            }

            /** Get the value
             */
            const T &operator *() const noexcept
            {
                return value;
            }

            /** Get the value
             */
            T *operator ->() noexcept
            {
                return &value;
            }

            /** Get the value
             */
            const T *operator ->() const noexcept
            {
                return &value;
            }

            virtual void Reset() noexcept override
            {
                PositionalBase::Reset();
                value = defaultValue;
            }
    };

    /** A positional argument mapping list class
     * 
     * \tparam K the type to extract the argument as
     * \tparam T the type to store the result as
     * \tparam List the list type that houses the values
     * \tparam Reader The functor type used to read the argument, taking the name, value, and destination reference with operator(), and returning a bool (if ARGS_NOEXCEPT is defined)
     * \tparam Map The Map type.  Should operate like std::map or std::unordered_map
     */
    template <
        typename K,
        typename T,
        template <typename...> class List = detail::vector,
        typename Reader = ValueReader,
        template <typename...> class Map = detail::unordered_map>
    class MapPositionalList : public PositionalBase
    {
        private:
            using Container = List<T>;

            const Map<K, T> map;
            Container values;
            const Container defaultValues;
            Reader reader;

        protected:
            virtual std::vector<std::string> GetChoicesStrings(const HelpParams &) const override
            {
                return detail::MapKeysToStrings(map);
            }

        public:
            typedef T value_type;
            typedef typename Container::allocator_type allocator_type;
            typedef typename Container::pointer pointer;
            typedef typename Container::const_pointer const_pointer;
            typedef T& reference;
            typedef const T& const_reference;
            typedef typename Container::size_type size_type;
            typedef typename Container::difference_type difference_type;
            typedef typename Container::iterator iterator;
            typedef typename Container::const_iterator const_iterator;
            typedef std::reverse_iterator<iterator> reverse_iterator;
            typedef std::reverse_iterator<const_iterator> const_reverse_iterator;

            MapPositionalList(Group &group_, const std::string &name_, const std::string &help_, const Map<K, T> &map_, const Container &defaultValues_ = Container(), Options options_ = {}):
                PositionalBase(name_, help_, options_), map(map_), values(defaultValues_), defaultValues(defaultValues_)
            {
                group_.Add(*this);
            }

            virtual ~MapPositionalList() {}

            virtual void ParseValue(const std::string &value_) override
            {
                K key;
#ifdef ARGS_NOEXCEPT
                if (!reader(name, value_, key))
                {
                    error = Error::Parse;
                }
#else
                reader(name, value_, key);
#endif
                auto it = map.find(key);
                if (it == std::end(map))
                {
                    std::ostringstream problem;
                    problem << "Could not find key '" << key << "' in map for arg '" << name << "'";
#ifdef ARGS_NOEXCEPT
                    error = Error::Map;
                    errorMsg = problem.str();
#else
                    throw MapError(problem.str());
#endif
                } else
                {
                    this->values.emplace_back(it->second);
                    matched = true;
                }
            }

            /** Get the value
             */
            Container &Get() noexcept
            {
                return values;
            }

            /** Get the value
             */
            Container &operator *() noexcept
            {
                return values;
            }

            /** Get the values
             */
            const Container &operator *() const noexcept
            {
                return values;
            }

            /** Get the values
             */
            Container *operator ->() noexcept
            {
                return &values;
            }

            /** Get the values
             */
            const Container *operator ->() const noexcept
            {
                return &values;
            }

            virtual std::string Name() const override
            {
                return name + std::string("...");
            }

            virtual void Reset() noexcept override
            {
                PositionalBase::Reset();
                values = defaultValues;
            }

            virtual PositionalBase *GetNextPositional() override
            {
                const bool wasMatched = Matched();
                auto me = PositionalBase::GetNextPositional();
                if (me && !wasMatched)
                {
                    values.clear();
                }
                return me;
            }

            iterator begin() noexcept
            {
                return values.begin();
            }

            const_iterator begin() const noexcept
            {
                return values.begin();
            }

            const_iterator cbegin() const noexcept
            {
                return values.cbegin();
            }

            iterator end() noexcept
            {
                return values.end();
            }

            const_iterator end() const noexcept 
            {
                return values.end();
            }

            const_iterator cend() const noexcept
            {
                return values.cend();
            }
    };
}

#endif
