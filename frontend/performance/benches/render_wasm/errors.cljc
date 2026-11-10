;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.errors
  "Failure values and plain diagnostics for renderer benchmarks.

  A step that can fail returns its value or an Error. `attempt` catches thrown
  values and `failure?` identifies the returned Error. Fatal failures mark
  writes to the owner's record or log and propagate to a boundary that uses
  `:catch-fatal`.

  `evidence` and `describe-cause` produce diagnostics for saved records and
  Transit responses."
  #?(:cljs (:require-macros
            [app.common.exceptions :as ex]
            [benches.render-wasm.errors])))

#?(:clj
   (defmacro attempt
     "Evaluates `body` and returns its value, or the failure it throws.

     A thrown value becomes an Error through `as-failure`. Fatal failures
     propagate unless a leading `:catch-fatal` requests them as values.
     Inside an `^:async` function, `body` may use `await`.

     Examples:

       (attempt (parse text))
       (attempt (await (open-page owner)))
       (attempt :catch-fatal (await (run-session state)))"
     [& forms]
     (let [catch-fatal? (= :catch-fatal (first forms))
           body         (if catch-fatal? (rest forms) forms)
           thrown       (gensym "thrown")
           failure      (gensym "failure")]
       `(try
          ~@body
          (catch :default ~thrown
            (let [~failure (benches.render-wasm.errors/as-failure ~thrown)]
              ~(if catch-fatal?
                 failure
                 `(if (benches.render-wasm.errors/fatal? ~failure)
                    (throw ~failure)
                    ~failure))))))))

#?(:cljs
   (do
     (def ^:private schema:cause-value
       [:or nil? boolean? keyword? number? string?])

     (def schema:cause-detail
       "One level of a portable exception description."
       [:map {:closed true}
        [:message string?]
        [:fn {:optional true} schema:cause-value]
        [:code {:optional true} schema:cause-value]
        [:type {:optional true} schema:cause-value]
        [:hint {:optional true} schema:cause-value]])

     (def ^:private diagnostic-fields
       [[:phase string?]
        [:message string?]
        [:operation {:optional true} keyword?]
        [:path {:optional true} [:string {:max 1024}]]
        [:status {:optional true} [:enum "failed" "stale"]]
        [:cause {:optional true} [:vector schema:cause-detail]]
        [:partial {:optional true} map?]
        [:reason {:optional true} :any]
        [:deadline-exceeded {:optional true} boolean?]
        [:expected-id {:optional true} [:string {:max 1024}]]
        [:actual-id {:optional true} [:string {:max 1024}]]
        [:command {:optional true} [:string {:max 1024}]]
        [:exit-code {:optional true} :int]
        [:pid {:optional true} [:int {:min 1}]]
        [:pgid {:optional true} [:int {:min 1}]]
        [:signals {:optional true} [:vector [:string {:max 1024}]]]
        [:profile-path {:optional true} [:string {:max 1024}]]
        [:fatal {:optional true} boolean?]
        [:unresolved-process {:optional true} boolean?]
        [:signal {:optional true} [:string {:max 1024}]]
        [:endpoint {:optional true} [:string {:max 1024}]]])

     (def schema:error-entry
       "One failure occurrence with its operation and portable diagnostics."
       (into [:map {:closed true}] diagnostic-fields))

     (def schema:diagnostic
       "Failure location, message and an optional ordered collection of errors."
       (into [:map]
             (conj diagnostic-fields
                   [:errors {:optional true} [:vector {:min 1} schema:error-entry]])))

     (def ^:private evidence-cause-depth 3)
     (def ^:private max-cause-chars 500)
     (def ^:private unrenderable-value "unrenderable value")
     (def ^:private unrenderable-cause-text "unrenderable cause")

     (defn failure?
       "True when `x` is an Error."
       [x]
       (instance? js/Error x))

     (defn message-of
       "Returns the error message or string form of `cause`, with a fallback
       when formatting fails. The default fallback is unknown failure."
       ([cause]
        (message-of cause "unknown failure"))
       ([cause fallback]
        (or (ex/ignoring (ex-message cause))
            (ex/ignoring (str cause))
            fallback)))

     (defn fatal-error
       "Returns an exception that `fatal?` recognizes, with `data` and `cause`."
       [message data cause]
       (ex-info message (assoc data :fatal true) cause))

     (defn fatal?
       "True when `cause` or one of its causes has the ex-data `:fatal` marker."
       [cause]
       (loop [cause cause
              seen  #{}]
         (cond
           (nil? cause) false
           (:fatal (ex-data cause)) true
           (contains? seen cause) false
           :else (recur (ex-cause cause) (conj seen cause)))))

     (defn as-failure
       "Returns an Error for `thrown`. Errors pass through; other values supply
       the message and the `:thrown` ex-data."
       [thrown]
       (if (failure? thrown)
         thrown
         (ex-info (if (string? thrown)
                    thrown
                    (str "non-error value thrown: " (or (ex/ignoring (pr-str thrown)) "unprintable value")))
                  {:thrown thrown})))

     (defn with-data
       "Adds default ex-data to an Error, keeping its message and using it as
       the cause. Existing keys win. Other values pass through."
       [x data]
       (if (failure? x)
         (ex-info (message-of x) (merge data (ex-data x)) x)
         x))

     (defn with-context
       "Returns an exception with `message` and added diagnostic context.
       New fields override existing fields; existing partial measurements win
       over earlier measurements in `data`. An exception with ex-data keeps
       its underlying cause. Other values become the cause."
       [cause message data]
       (let [existing (ex-data cause)
             context  (cond-> (merge existing data)
                        (contains? data :partial)
                        (assoc :partial (merge (:partial data) (:partial existing))))]
         (ex-info message context (if (some? existing) (ex-cause cause) cause))))

     (defn- merge-error-context
       [outer inner]
       (cond-> (merge outer inner)
         (or (:partial outer) (:partial inner))
         (assoc :partial (merge (:partial outer) (:partial inner)))))

     (defn- get-error-collection
       [cause]
       (loop [current cause
              seen    #{}
              context {}]
         (let [data    (ex-data current)
               context (merge-error-context context (dissoc data ::errors))]
           (if-let [entries (::errors data)]
             (update entries 0 #(merge-error-context context %))
             (when (and (ex-cause current) (not (contains? seen current)))
               (recur (ex-cause current) (conj seen current) context))))))

     (defn get-errors
       "Returns failure occurrences in order, retaining their Error objects.

       Args:
         cause: An Error, including one wrapped with additional context.

       Returns:
         A flat vector of maps with an Error under :error and available phase,
         operation and path context."
       [cause]
       (or (get-error-collection cause)
           [(assoc (select-keys (ex-data cause) [:phase :operation :path]) :error cause)]))

     (defn combine
       "Combines failure occurrences into an Error, or returns nil on success.

       Args:
         entries: Ordered maps with :error and optional :phase, :operation and
           :path. Nil errors represent successful operations. An Error may
           contain a collection from an earlier operation.

       Returns:
         An Error retaining a flat collection and the first error's message
         and data. Retained Error objects expose their causes. Any fatal
         occurrence makes the combined error fatal."
       [entries]
       (let [sources (filterv #(failure? (:error %)) entries)
             error   (:error (first sources))
             entries (reduce (fn [collected {:keys [error] :as entry}]
                               (let [context (dissoc entry :error)
                                     group   (get-errors error)
                                     group   (if (seq context)
                                               (mapv #(merge-error-context context %) group)
                                               group)]
                                 (if (empty? collected) group (into collected group))))
                             [] sources)]
         (when error
           (ex-info (message-of error)
                    (cond-> (merge (select-keys (first entries) [:phase :operation :path])
                                   (ex-data error)
                                   {::errors entries})
                      (some #(fatal? (:error %)) sources) (assoc :fatal true))
                    (if (::errors (ex-data error)) (ex-cause error) error)))))

     (defn stale?
       "True when the exception's own data marks superseded work."
       [cause]
       (true? (::stale (ex-data cause))))

     (defn fail-data
       "Marks diagnostic data with the failed status."
       [data]
       (assoc data :status "failed"))

     (defn bounded-text
       "Returns text within a size limit, preserving UTF-16 boundaries.

       Converts `value` to a string and limits it to max-chars UTF-16 code
       units. If conversion fails, it returns 'unrenderable value'. When
       truncating, we check whether the cut would leave a high surrogate
       without its matching low surrogate. If so, we remove that last code unit
       to keep the pair intact.

       Args:
         value: Value to convert to text.
         max-chars: Positive limit in UTF-16 units.

       Returns:
         Bounded text, or a fallback string when conversion fails."
       [value max-chars]
       (or (ex/ignoring
            (let [text (str value)]
              (if (<= (count text) max-chars)
                text
                (let [cut  (subs text 0 max-chars)
                      last (.charCodeAt cut (dec max-chars))]
                  (if (and (>= last 0xD800)
                           (<= last 0xDBFF)
                           (let [nxt (.charCodeAt text max-chars)]
                             (and (>= nxt 0xDC00) (<= nxt 0xDFFF))))
                    (subs cut 0 (dec max-chars))
                    cut)))))
           unrenderable-value))

     (defn project-context
       "Returns bounded identity, process and ownership details.

       Args:
         data: Diagnostic data with available identity or process fields.

       Returns:
         Bounded text, integer process IDs and exit code, attempted signals,
         and available fatal or unresolved-ownership markers."
       [data]
       (cond-> {}
         (string? (:expected-id data)) (assoc :expected-id (bounded-text (:expected-id data) 1024))
         (string? (:actual-id data)) (assoc :actual-id (bounded-text (:actual-id data) 1024))
         (string? (:command data)) (assoc :command (bounded-text (:command data) 1024))
         (integer? (:exit-code data)) (assoc :exit-code (:exit-code data))
         (and (integer? (:pid data)) (pos? (:pid data))) (assoc :pid (:pid data))
         (and (integer? (:pgid data)) (pos? (:pgid data))) (assoc :pgid (:pgid data))
         (and (vector? (:signals data)) (every? string? (:signals data)))
         (assoc :signals (mapv #(bounded-text % 1024) (:signals data)))
         (string? (:profile-path data)) (assoc :profile-path (bounded-text (:profile-path data) 1024))
         (true? (:fatal data)) (assoc :fatal true)
         (true? (:unresolved-process data)) (assoc :unresolved-process true)
         (string? (:signal data)) (assoc :signal (bounded-text (:signal data) 1024))
         (string? (:endpoint data)) (assoc :endpoint (bounded-text (:endpoint data) 1024))))

     (defn project-diagnostic
       "Returns the diagnostic fields of an execution envelope.

       Args:
         evidence: Plain execution evidence.

       Returns:
         Failure status, location, message and available diagnostic details."
       [evidence]
       (cond-> (merge (select-keys evidence [:status :phase :message :cause :partial :reason :deadline-exceeded
                                             :operation :errors])
                      (project-context evidence))
         (string? (:path evidence)) (assoc :path (bounded-text (:path evidence) 1024))))

     (defn- sanitize-cause-value
       [value]
       (cond
         (or (nil? value) (boolean? value) (keyword? value) (number? value))
         value

         (string? value)
         (bounded-text value max-cause-chars)

         :else
         (bounded-text (or (ex/ignoring (pr-str value)) unrenderable-value) max-cause-chars)))

     (defn- describe-cause-level
       [cause]
       (or (ex/ignoring
            (let [message (ex/ignoring (ex-message cause))
                  data    (ex/ignoring (ex-data cause))
                  text    (cond
                            (string? message) message
                            (string? cause)   cause
                            (nil? cause)      "unknown failure"
                            :else             (pr-str cause))
                  out     {:message (sanitize-cause-value text)}]
              (if (map? data)
                (reduce (fn [m k]
                          (if (contains? data k)
                            (assoc m k (sanitize-cause-value (get data k)))
                            m))
                        out
                        [:fn :code :type :hint])
                out)))
           {:message unrenderable-cause-text}))

     (defn describe-cause
       "Returns up to three cause levels as plain data. Each level keeps a
       bounded message and available :fn, :code, :type and :hint details.
       Unprintable values produce fallback strings."
       [cause]
       (or (ex/ignoring
            (loop [current cause
                   depth   0
                   acc     []]
              (if (or (nil? current) (>= depth evidence-cause-depth))
                (if (seq acc)
                  acc
                  [{:message "unknown failure"}])
                (let [level (describe-cause-level current)
                      next  (ex/ignoring (ex-cause current))]
                  (recur next (inc depth) (conj acc level))))))
           [{:message unrenderable-cause-text}]))

     (defn- diagnostic-data
       [phase cause]
       (let [partial (ex/ignoring (:partial (ex-data cause)))]
         (cond-> (merge (project-context (ex-data cause))
                        (select-keys (ex-data cause) [:operation])
                        {:phase (if (keyword? phase) (name phase) (str phase))
                         :message (message-of cause)
                         :cause (describe-cause cause)})
           partial (assoc :partial partial)
           (string? (:path (ex-data cause)))
           (assoc :path (bounded-text (:path (ex-data cause)) 1024)))))

     (defn evidence
       "Returns plain diagnostics, including ordered failure occurrences.

       Args:
         phase: Failure location for the enclosing operation.
         cause: Error with available operation context and collected failures.

       Returns:
         Portable diagnostics with bounded cause descriptions. Collected
         errors retain occurrence order and messages within 1024 UTF-16 units."
       [phase cause]
       (let [entries   (get-error-collection cause)
             projected (when entries
                         (mapv (fn [{:keys [error operation path partial] entry-phase :phase :as entry}]
                                 (cond-> (merge (diagnostic-data (or entry-phase phase) error)
                                                (project-context entry)
                                                (select-keys entry [:reason :deadline-exceeded])
                                                {:message (bounded-text (message-of error) 1024)})
                                   operation (assoc :operation operation)
                                   partial (assoc :partial partial)
                                   (string? path) (assoc :path (bounded-text path 1024))))
                               entries))]
         (cond-> (diagnostic-data phase cause)
           projected (assoc :errors projected)
           (some :unresolved-process projected) (assoc :unresolved-process true))))))
