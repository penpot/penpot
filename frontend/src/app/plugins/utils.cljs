;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.plugins.utils
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.i18n :as i18n :refer [tr]]
   [app.common.schema :as sm]
   [app.common.schema.messages :as csm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(defn locate-file
  [id]
  (assert (uuid? id) "File not valid uuid")
  (dsh/lookup-file @st/state id))

(defn locate-page
  [file-id id]
  (assert (uuid? id) "Page not valid uuid")
  (-> (dsh/lookup-file-data @st/state file-id)
      (dsh/get-page id)))

(defn locate-objects
  ([]
   (locate-objects (:current-file-id @st/state) (:current-page-id @st/state)))
  ([file-id page-id]
   (:objects (locate-page file-id page-id))))

(defn locate-shape
  [file-id page-id id]
  (assert (uuid? id) "Shape not valid uuid")
  (dm/get-in (locate-page file-id page-id) [:objects id]))

(defn page-active?
  "Returns true if `page-id` is the currently active page. Plugin structural
  operations only affect the active page, so callers use this to reject
  attempts to modify shapes that live on a different page."
  [page-id]
  (= page-id (:current-page-id @st/state)))

(defn locate-library-color
  [file-id id]
  (assert (uuid? id) "Color not valid uuid")
  (dm/get-in (locate-file file-id) [:data :colors id]))

(defn locate-library-typography
  [file-id id]
  (assert (uuid? id) "Typography not valid uuid")
  (dm/get-in (locate-file file-id) [:data :typographies id]))

(defn locate-library-component
  [file-id id]
  (assert (uuid? id) "Component not valid uuid")
  (dm/get-in (locate-file file-id) [:data :components id]))

(defn locate-tokens-lib
  [file-id]
  (let [file (locate-file file-id)]
    (->> file :data :tokens-lib)))

(defn locate-token-theme
  [file-id id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (when (some? tokens-lib)
      (ctob/get-theme tokens-lib id))))

(defn locate-token-set
  [file-id set-id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (when (some? tokens-lib)
      (ctob/get-set tokens-lib set-id))))

(defn locate-token
  [file-id set-id token-id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (when (some? tokens-lib)
      (ctob/get-token tokens-lib set-id token-id))))

(defn locate-presence
  [session-id]
  (dm/get-in @st/state [:workspace-presence session-id]))

(defn locate-profile
  [session-id]
  (let [{:keys [profile-id]} (locate-presence session-id)]
    (dm/get-in @st/state [:profiles profile-id])))

;; FIXME: the impl looks strange: objects is passed by parameters but
;; then the rest of the file is looked up directly from state.... (?)
(defn locate-component
  [objects shape]
  (let [state           (deref st/state)
        file            (dsh/lookup-file state)
        libraries       (dsh/lookup-libraries state)
        root            (ctn/get-instance-root objects shape)]
    [root (ctf/resolve-component root file libraries {:include-deleted? true})]))

(defn locate-head-component
  "Like locate-component but resolves via the nearest component head
  instead of the outermost instance root."
  [objects shape]
  (let [state           (deref st/state)
        file            (dsh/lookup-file state)
        libraries       (dsh/lookup-libraries state)
        head            (ctn/get-head-shape objects shape)]
    (when head
      [head (ctf/resolve-component head file libraries {:include-deleted? true})])))

(defn proxy->file
  [proxy]
  (let [id (obj/get proxy "$id")]
    (when (some? id)
      (locate-file id))))

(defn proxy->page
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-page file-id id))))

(defn proxy->shape
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        id      (obj/get proxy "$id")]
    (when (and (some? file-id) (some? page-id) (some? id))
      (locate-shape file-id page-id id))))

(defn inside-component-copy?
  "True when `shape` is nested inside a component copy. The copy root itself is
  movable as a whole; its descendants are structural copy content and must not
  be reparented by the Plugin API."
  [objects shape]
  (boolean (ctn/has-any-copy-parent? objects shape)))

(defn component-copy-container?
  "True when changing `shape`'s children would alter a component copy structure."
  [shape]
  (boolean (ctk/in-component-copy? shape)))

(defn changes-component-copy-structure?
  "Returns true when moving `child` into `parent` would either alter a copy
  container or move an existing child out of/within a component copy."
  [objects parent child]
  (or (component-copy-container? parent)
      (inside-component-copy? objects child)))

(defn proxy->library-color
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-color file-id id))))

(defn proxy->library-typography
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-typography file-id id))))

(defn proxy->library-component
  [proxy]
  (let [file-id (obj/get proxy "$file")
        id (obj/get proxy "$id")]
    (when (and (some? file-id) (some? id))
      (locate-library-component file-id id))))

(defn proxy->flow
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        flow-id (obj/get proxy "$id")
        page (locate-page file-id page-id)]
    (when (some? page)
      (get (:flows page) flow-id))))

(defn locate-ruler-guide
  [file-id page-id ruler-id]
  (let [page (locate-page file-id page-id)]
    (when (some? page)
      (d/seek #(= (:id %) ruler-id) (-> page :guides vals)))))

(defn proxy->ruler-guide
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        ruler-id (obj/get proxy "$id")]
    (locate-ruler-guide file-id page-id ruler-id)))

(defn locate-interaction
  [file-id page-id shape-id index]
  (when-let [shape (locate-shape file-id page-id shape-id)]
    (get-in shape [:interactions index])))

(defn proxy->interaction
  [proxy]
  (let [file-id (obj/get proxy "$file")
        page-id (obj/get proxy "$page")
        shape-id (obj/get proxy "$shape")
        index (obj/get proxy "$index")]
    (locate-interaction file-id page-id shape-id index)))

(defn get-data
  ([self attr]
   (-> (obj/get self "_data")
       (get attr)))

  ([self attr transform-fn]
   (-> (get-data self attr)
       (transform-fn))))

(defn get-data-fn
  ([attr]
   (fn [self]
     (get-data self attr)))

  ([attr transform-fn]
   (fn [self]
     (get-data self attr transform-fn))))

(defn get-state
  ([self attr]
   (let [id      (get-data self :id)
         page-id (or (get-data self :page-id)
                     (:current-page-id @st/state))]
     (-> (dsh/lookup-page-objects @st/state page-id)
         (dm/get-in [:objects id attr]))))

  ([self attr mapfn]
   (-> (get-state self attr)
       (mapfn))))

(defn result-p
  "Creates a pair of atom+promise. The promise will be resolved when the atom gets a value.
  We use this to return the promise to the library clients and resolve its value when a value is passed
  to the atom"
  []
  (let [ret-v (atom nil)
        ret-p
        (js/Promise.
         (fn [resolve _]
           (add-watch
            ret-v
            ::watcher
            (fn [_ _ _ value]
              (remove-watch ret-v ::watcher)
              (resolve value)))))]
    [ret-v ret-p]))

(defn natural-child-ordering?
  [plugin-id]
  (boolean
   (dm/get-in @st/state [:plugins :flags plugin-id :natural-child-ordering])))

(defn throw-validation-errors?
  [plugin-id]
  (boolean
   (dm/get-in @st/state [:plugins :flags plugin-id :throw-validation-errors])))

(defn display-not-valid
  [code value]
  (if (some? value)
    (.error js/console (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code))
    (.error js/console (dm/str "[PENPOT PLUGIN] Value not valid. Code: " code)))
  nil)

(defn throw-not-valid
  [code value]
  (if (some? value)
    (throw (js/Error. (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code)))
    (throw (js/Error. (dm/str "[PENPOT PLUGIN] Value not valid. Code: " code))))
  nil)

(defn not-valid
  [plugin-id code value]
  (if (throw-validation-errors? plugin-id)
    (throw-not-valid code value)
    (display-not-valid code value)))

(defn reject-not-valid
  [reject code value]
  (let [msg (dm/str "[PENPOT PLUGIN] Value not valid: " value ". Code: " code)]
    (.error js/console msg)
    (reject msg)))

(defn mixed-value
  [values]
  (let [s (set values)]
    (if (= (count s) 1) (first s) "mixed")))

(defn- flatten-error-map
  "Walk an error map produced by `csm/interpret-schema-problem` and yield
  `[path message]` pairs, where `path` is the dot-joined field path
  (e.g. `:group` -> \"group\", `[:sets 0 :name]` -> \"sets.0.name\").

  `interpret-schema-problem` calls `(assoc-in acc field {:message …})`, so
  when the malli error path has more than one element the resulting map is
  nested (e.g. `{:sets {0 {:name {:message \"…\"}}}}`); when the path has
  a single element it is flat (`{:group {:message \"…\"}}`). The plugin
  error-message renderer needs both cases reduced to per-leaf
  `[path message]` pairs so it can produce one `plugins.validation.message`
  string per actual validation problem."
  ([m] (flatten-error-map [] m))
  ([prefix m]
   (mapcat
    (fn [[k v]]
      (let [segment (cond
                      (keyword? k) (name k)
                      (string?  k) k
                      :else        (str k))
            path    (conj prefix segment)]
        (if (and (map? v) (not (contains? v :message)))
          (flatten-error-map path v)
          [[(str/join "." path) (:message v)]])))
    m)))

(def ^:private max-repr-length 100)
(def ^:private max-repr-depth 2)
(def ^:private max-repr-items 5)

(defn- abbreviate
  "Shorten `s` to `max-repr-length` code points. Cutting on a UTF-16 code
  unit would split surrogate pairs and render astral characters as mojibake."
  [s]
  (if (> (count s) max-repr-length)
    (let [points (js/Array.from s)]
      (if (> (alength points) max-repr-length)
        (dm/str (.join (.slice points 0 max-repr-length) "") "…")
        s))
    s))

(defn- value->type
  [value]
  (cond
    (string? value)             "string"
    (boolean? value)            "boolean"
    (number? value)             "number"
    (keyword? value)            "keyword"
    (map? value)                "object"
    (coll? value)               "array"
    (array? value)              "array"
    (fn? value)                 "function"
    (instance? js/Object value) "object"
    :else                       "unknown"))

(defn- data-property
  "Own property `k` of the JS object `o`, or `::skip` when `k` is an accessor.
  Plugin proxies expose their contents through getters that read the
  application state and may throw, so the error path must not run them."
  [o k]
  (let [descriptor (js/Object.getOwnPropertyDescriptor o k)]
    (if (and (some? descriptor)
             (undefined? (unchecked-get descriptor "get")))
      (unchecked-get descriptor "value")
      ::skip)))

(defn- value->repr
  "Bounded representation of a value received from a plugin. Such values are
  arbitrary JS data: `pr-str` never returns on a self referencing object
  (`a.parent.child === a`), so the traversal is capped in depth and in width
  and never descends into an ancestor."
  ([value]
   (value->repr value 0 []))
  ([value depth seen]
   (cond
     (string? value)
     (pr-str (abbreviate value))

     (or (nil? value) (number? value) (boolean? value) (keyword? value))
     (pr-str value)

     (fn? value)
     "#function"

     (some #(identical? % value) seen)
     "#recursive"

     (>= depth max-repr-depth)
     "…"

     (map? value)
     (let [seen (conj seen value)]
       (dm/str "{" (->> (take max-repr-items value)
                        (map (fn [[k v]]
                               (dm/str (value->repr k (inc depth) seen) " "
                                       (value->repr v (inc depth) seen))))
                        (str/join ", "))
               (when (> (count value) max-repr-items) ", …") "}"))

     (or (array? value) (coll? value))
     (let [seen  (conj seen value)
           items (if (array? value) (array-seq value) (seq value))]
       (dm/str "[" (->> (take max-repr-items items)
                        (map #(value->repr % (inc depth) seen))
                        (str/join " "))
               (when (seq (drop max-repr-items items)) " …") "]"))

     (instance? js/Object value)
     (let [seen (conj seen value)
           ks   (js/Object.keys value)]
       (dm/str "{" (->> (take max-repr-items ks)
                        (keep (fn [k]
                                (let [v (data-property value k)]
                                  (when-not (= ::skip v)
                                    (dm/str k " " (value->repr v (inc depth) seen))))))
                        (str/join ", "))
               (when (> (alength ks) max-repr-items) ", …") "}"))

     :else
     (value->type value))))

(defn- printable?
  "True when a schema form contains only data, so it can be shown to a plugin
  author. `[:fn pred]` forms embed the predicate itself, which prints as an
  unreadable `#object[…]` with the internal munged name."
  [form]
  (cond
    (map? form)      (every? printable? (vals form))
    (coll? form)     (every? printable? form)
    (keyword? form)  true
    (string? form)   true
    (number? form)   true
    (boolean? form)  true
    (symbol? form)   true
    (regexp? form)   true
    (nil? form)      true
    :else            false))

(defn- simplify-form
  "Drop from a schema form the properties that are not data, so a schema is
  described by its shape alone: `[::sm/text {:error/fn f}]` becomes
  `::sm/text`. Yields `::unprintable` when what is left cannot describe the
  schema, as in `[:fn pred]`, where dropping the predicate would advertise a
  schema (`fn`) that means nothing to a plugin author."
  [form]
  (cond
    (map? form)
    (not-empty (into {} (filter (comp printable? val)) form))

    (vector? form)
    (let [items (into [] (keep simplify-form) form)]
      (cond
        (some #(= ::unprintable %) items) ::unprintable
        (= 1 (count items))               (first items)
        :else                             items))

    (printable? form)
    form

    :else
    ::unprintable))

(defn- expected-form
  "Readable representation of the schema a problem failed against, or nil when
  the schema cannot be shown."
  [schema]
  (let [title (or (:title (sm/properties schema))
                  (:title (sm/type-properties schema)))]
    (if (some? title)
      title
      (let [form (simplify-form (sm/form schema))]
        (cond
          (= ::unprintable form) nil
          (keyword? form)        (name form)
          :else                  (pr-str form))))))

(defn- schema-message
  "Message rendered by `csm/interpret-schema-problem` for a problem, or nil when
  it degrades to the generic \"invalid data\": the token value schemas declare an
  `:error/fn` that only speaks about empty values, so it renders nothing at all
  for a wrong typed one, and saying nothing must not win over reporting what was
  expected and what was received (#10072)."
  [problem field]
  (let [message (-> (csm/interpret-schema-problem {} problem)
                    (get-in field)
                    (get :message))]
    (when (and (some? message)
               (not= message (tr "errors.invalid-data")))
      message)))

(defn- interpret-problem
  "Like `csm/interpret-schema-problem`, but when the schema renders no message of
  its own it reports the expected schemas together with the received value and
  its type instead of a generic \"Invalid data\" (#10072).

  Malli reports one problem per `:or` branch, all on the same field, so the
  branches are accumulated: reporting only one of them would tell the plugin
  author that an alternative that is in fact valid is not accepted."
  [acc {:keys [schema in value] :as problem}]
  (let [field   (or (:error/field (sm/properties schema)) in)
        field   (if (vector? field) field [field])
        current (when (seq field) (get-in acc field))]
    (cond
      (empty? field)
      acc

      ;; A message the schema renders itself always wins over the generic
      ;; expected/received one, and is never overwritten by it.
      (and (some? current) (not (contains? current :expected)))
      acc

      :else
      (if-let [message (schema-message problem field)]
        (assoc-in acc field {:message message})
        (let [form      (expected-form schema)
              complete? (and (get current :complete? true) (some? form))
              expected  (if complete?
                          (-> (get current :expected [])
                              (conj form)
                              (distinct)
                              (vec))
                          [])
              received  (value->type value)
              repr      (abbreviate (value->repr value))
              message   (if (seq expected)
                          (tr "plugins.validation.invalid-value"
                              (abbreviate (str/join " or " expected))
                              received repr)
                          (tr "plugins.validation.received-value" received repr))]
          (assoc-in acc field {:message   message
                               :expected  expected
                               :complete? complete?}))))))

(defn error-messages
  [explain]
  (let [msg (->> (:errors explain)
                 (reduce interpret-problem {})
                 (flatten-error-map)
                 (map (fn [[field message]]
                        (tr "plugins.validation.message" field message)))
                 (str/join ". "))]
    ;; Return nil (not "") when the explain has no mappable errors, so
    ;; `handle-error` can fall back to a non-empty message instead of
    ;; surfacing a bare "Value not valid. Code: :error" (#9692).
    (when-not (str/blank? msg) msg)))

(defn handle-error
  "Function to be used in plugin proxies methods to handle errors and print a readable
   message to the console."
  [plugin-id]
  (fn [cause]
    (let [explain (-> cause ex-data ::sm/explain)
          throw? (throw-validation-errors? plugin-id)]
      (cond
        ;; If it's a clojure error we throw as a validation error
        (and throw? explain)
        (throw-not-valid :error (error-messages explain))

        ;; Unexpected errors we just propagate them
        throw?
        (throw cause)

        ;; If not throw is active we log the caught error
        :else
        (let [message
              (if explain
                (do
                  (js/console.error (sm/humanize-explain explain))
                  (or (error-messages explain) (pr-str explain)))
                (or (ex-data cause) (ex-message cause) (str cause)))]
          (js/console.log (.-stack cause))
          (not-valid plugin-id :error message))))))

(defn is-main-component-proxy?
  [p]
  (when-let [shape (proxy->shape p)]
    (ctk/main-instance? shape)))
