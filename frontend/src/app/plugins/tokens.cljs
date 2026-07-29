;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.plugins.tokens
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cfo]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.plugins.system-events :as se]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [clojure.datafy :refer [datafy]]
   [clojure.set :refer [map-invert]]))

;; === Token

;; Give more semantic names to the shape attributes that tokens can be applied to
(def ^:private map:token-attr->token-attr-plugin
  {:r1 :border-radius-top-left
   :r2 :border-radius-top-right
   :r3 :border-radius-bottom-right
   :r4 :border-radius-bottom-left

   :p1 :padding-top
   :p2 :padding-right
   :p3 :padding-bottom
   :p4 :padding-left

   :m1 :margin-top
   :m2 :margin-right
   :m3 :margin-bottom
   :m4 :margin-left})

(def ^:private map:token-attr-plugin->token-attr
  (merge
   (map-invert map:token-attr->token-attr-plugin)
   {:padding-top    :p1
    :padding-right  :p2
    :padding-bottom :p3
    :padding-left   :p4
    :margin-top     :m1
    :margin-right   :m2
    :margin-bottom  :m3
    :margin-left    :m4}))

(defn token-attr->token-attr-plugin
  [k]
  (get map:token-attr->token-attr-plugin k k))

(defn token-attr-plugin->token-attr
  "Resolve a plugin-side token attribute reference to its canonical
  internal keyword.

  Accepts either a Clojure keyword (the canonical form, e.g. `:r1`,
  `:fill`) or a string (the natural shape that arrives from a JS plugin
  call such as `shape.applyToken(token, [\"fill\"])`). Converts strings
  to keywords first, then maps verbose plugin-side aliases (e.g.
  `:border-radius-top-left`) to their internal short form (e.g. `:r1`).
  Inputs that are already in canonical form (`:r1`, `:fill`, `\"fill\"`,
  …) pass through unchanged."
  [k]
  (let [k (cond-> k (string? k) keyword)]
    (get map:token-attr-plugin->token-attr k k)))

(defn applied-tokens-plugin->applied-tokens
  [value]
  (into {}
        (map (fn [[k v]] [(token-attr->token-attr-plugin k) v]))
        value))

(defn token-attr?
  [attr]
  (cto/token-attr? (token-attr-plugin->token-attr attr)))

(defn- apply-token-to-shapes
  [plugin-id file-id set-id id shape-ids attrs]

  (let [token (u/locate-token file-id set-id id)]
    (if (some #(not (token-attr? %)) attrs)
      (u/not-valid plugin-id :applyToSelected attrs)
      (st/emit!
       (-> (dwta/toggle-token {:token token
                               :attrs (into #{} (map token-attr-plugin->token-attr) attrs)
                               :shape-ids shape-ids
                               :expand-with-children false})
           (se/add-event plugin-id))))))

(defn- typography-resolved-value->js
  "Converts a resolved typography composite (a Clojure map keyed by the
   tokenscript field names) into the plugin's `TokenTypographyValue[]` shape: a
   JS array with a single object using the public camelCase member names."
  [m]
  (when (map? m)
    #js [#js {"fontFamilies"   (clj->js (:font-family m))
              "fontSizes"      (:font-size m)
              "fontWeights"    (some-> (:font-weight m) str)
              "letterSpacing"  (:letter-spacing m)
              "lineHeight"     (:line-height m)
              "textCase"       (:text-case m)
              "textDecoration" (:text-decoration m)}]))

(defn- shadow-key->camel
  "Renames a shadow composite field name (kebab string) to its public camelCase
   member name. The shadow schema is closed; offset-x/offset-y are its only
   multi-word fields, so the rest (blur, spread, color, inset) pass through."
  [k]
  (case k
    "offset-x" "offsetX"
    "offset-y" "offsetY"
    k))

(defn- shadow-entry->js
  "Converts one resolved shadow entry (a JS Map of field name -> tokenscript
   symbol) into a plain JS object using the public member names and the
   unit-converted values."
  [^js m]
  (let [out #js {}]
    (.forEach m (fn [sym k]
                  (obj/set! out (shadow-key->camel k)
                            (ts/tokenscript-symbols->penpot-unit sym))))
    out))

(defn- shadow-resolved-value->js
  "Converts a resolved shadow composite (a sequence of shadow entries) into the
   plugin's `TokenShadowValue[]` shape."
  [entries]
  (when (some? entries)
    (into-array (map shadow-entry->js entries))))

(defn- font-families-resolved-value->js
  "Converts a resolved fontFamilies value (a tokenscript list symbol) into the
   documented `string[]` shape rather than leaking the raw tokenscript structure."
  [resolved-value]
  (let [v (ts/tokenscript-symbols->penpot-unit resolved-value)]
    (cond
      (nil? v) nil
      (sequential? v) (clj->js v)
      :else #js [v])))

(defn- get-resolved-value
  [token tokens-tree]
  (let [resolved-tokens (ts/resolve-tokens tokens-tree)
        resolved-value  (dm/get-in resolved-tokens [(:name token) :resolved-value])]
    (cond
      (= :font-family (:type token))
      ;; A fontFamilies token resolves to a list of families; expose it as the
      ;; documented `string[]` rather than the raw tokenscript list symbol.
      (font-families-resolved-value->js resolved-value)

      (= :typography (:type token))
      ;; A typography token resolves to a composite; expose it as the documented
      ;; `TokenTypographyValue[]` rather than the raw tokenscript structure.
      (typography-resolved-value->js
       (ts/tokenscript-symbols->penpot-unit resolved-value))

      (= :shadow (:type token))
      ;; A shadow token resolves to a list of composites whose entries the
      ;; tokenscript unit conversion leaves as raw symbols; expose them as the
      ;; documented `TokenShadowValue[]`.
      (shadow-resolved-value->js
       (ts/tokenscript-symbols->penpot-unit resolved-value))

      :else
      (ts/tokenscript-symbols->penpot-unit resolved-value))))

(defn token-proxy? [p]
  (obj/type-of? p "TokenProxy"))

;; Cannot use shape/shape-proxy? here because of circular dependency in applyToken in shape proxy
(defn shape-proxy? [s]
  (obj/type-of? s "ShapeProxy"))

(defn token-proxy
  [plugin-id file-id set-id id]
  (obj/reify {:name "TokenProxy"
              :on-error (u/handle-error plugin-id)}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$set-id {:enumerable false :get (constantly set-id)}
    :$id {:enumerable false :get (constantly id)}

    :id
    {:get #(dm/str id)}

    :name
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (ctob/get-name token)))
     :schema (cfo/make-token-name-schema
              (some-> (u/locate-tokens-lib file-id)
                      (ctob/get-tokens set-id)))
     :set
     (fn [_ value]
       (st/emit! (-> (dwtl/update-token set-id id {:name value})
                     (se/add-event plugin-id))))}

    :type
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (-> (:type token) (cto/token-type->dtcg-token-type))))}

    :value
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (json/->js (:value token))))
     :schema (let [token (u/locate-token file-id set-id id)
                   base  (cfo/make-token-value-schema (:type token))]
               ;; plugin-types declares the fontFamilies value as
               ;; `string | string[]`, but the core schema only accepts a
               ;; vector/ref; also accept a plain string (normalized in :set).
               (if (= :font-family (:type token))
                 [:or :string base]
                 base))
     :set
     (fn [_ value]
       (let [token (u/locate-token file-id set-id id)
             value (cond-> value
                     (= :font-family (:type token))
                     (ctob/convert-dtcg-font-family))]
         (st/emit! (dwtl/update-token set-id id {:value value}))))}

    :resolvedValue
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [token           (u/locate-token file-id set-id id)
             tokens-lib      (u/locate-tokens-lib file-id)
             tokens-tree     (ctob/get-tokens-in-active-sets tokens-lib)]
         (get-resolved-value token tokens-tree)))}

    :resolvedValueString
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [token           (u/locate-token file-id set-id id)
             tokens-lib      (u/locate-tokens-lib file-id)
             tokens-tree     (ctob/get-tokens-in-active-sets tokens-lib)]
         (str (get-resolved-value token tokens-tree))))}

    :description
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (ctob/get-description token)))
     :schema cfo/schema:token-description
     :set
     (fn [_ value]
       (st/emit! (-> (dwtl/update-token set-id id {:description value})
                     (se/add-event :plugin-id))))}

    :duplicate
    (fn []
      ;; TODO:
      ;;  - add function duplicate-token in tokens-lib, that allows to specify the new id
      ;;  - use this function in dwtl/duplicate-token
      ;;  - return the new token proxy using the locally forced id
      ;;  - do the same with sets and themes
      (let [token  (u/locate-token file-id set-id id)
            token' (ctob/make-token (-> (datafy token)
                                        (dissoc :id
                                                :modified-at)))]
        (st/emit! (-> (dwtl/create-token set-id token')
                      (se/add-event plugin-id)))
        (token-proxy plugin-id file-id set-id (:id token'))))

    :remove
    (fn []
      (st/emit! (-> (dwtl/delete-token set-id id)
                    (se/add-event plugin-id))))

    :applyToShapes
    {:enumerable false
     :schema [:tuple
              [:vector [:fn shape-proxy?]]
              [:maybe [::sm/set [:and ::sm/keyword [:fn token-attr?]]]]]
     :fn (fn [shapes attrs]
           (apply-token-to-shapes plugin-id file-id set-id id (map #(obj/get % "$id") shapes) attrs))}

    :applyToSelected
    {:enumerable false
     :schema [:tuple [:maybe [::sm/set [:and ::sm/keyword [:fn token-attr?]]]]]
     :fn (fn [attrs]
           (let [selected (get-in @st/state [:workspace-local :selected])]
             (apply-token-to-shapes plugin-id file-id set-id id selected attrs)))}))

;; === Token Set

(defn token-set-proxy? [p]
  (obj/type-of? p "TokenSetProxy"))

(defn token-set-proxy
  ([plugin-id file-id id]
   (token-set-proxy plugin-id file-id id nil))
  ([plugin-id file-id id initial-name]
   (obj/reify {:name "TokenSetProxy"
               :on-error (u/handle-error plugin-id)}
     :$plugin {:enumerable false :get (constantly plugin-id)}
     :$file-id {:enumerable false :get (constantly file-id)}
     :$id {:enumerable false :get (constantly id)}

     :id
     {:get #(dm/str id)}

     :name
     {:this true
      :get
      (fn [_]
        ;; Prefer the authoritative state lookup; fall back to initial-name
        ;; when the async state update from `catalog.addSet()` hasn't
        ;; propagated yet.
        (let [set (u/locate-token-set file-id id)]
          (if (some? set)
            (ctob/get-name set)
            initial-name)))
      :schema (cfo/make-token-set-name-schema
               (u/locate-tokens-lib file-id)
               id)
      :set
      (fn [_ name]
        (let [set (u/locate-token-set file-id id)]
          (st/emit! (dwtl/rename-token-set set name))))}

     :active
     {:this true
      :enumerable false
      :get
      (fn [_]
        (let [tokens-lib (u/locate-tokens-lib file-id)
              set        (u/locate-token-set file-id id)]
          (ctob/token-set-active? tokens-lib (ctob/get-name set))))
      :schema ::sm/boolean
      :set
      (fn [_ value]
        (let [set (u/locate-token-set file-id id)]
          (st/emit! (dwtl/set-enabled-token-set (ctob/get-name set) value))))}

     :toggleActive
     (fn [_]
       (let [set (u/locate-token-set file-id id)]
         (st/emit! (dwtl/toggle-token-set (ctob/get-name set)))))

     :tokens
     {:this true
      :enumerable false
      :get
      (fn [_]
        (let [tokens-lib (u/locate-tokens-lib file-id)]
          (->> (ctob/get-tokens tokens-lib id)
               (vals)
               (map #(token-proxy plugin-id file-id id (:id %)))
               (apply array))))}

     :tokensByType
     {:this true
      :enumerable false
      :get
      (fn [_]
        (let [tokens-lib (u/locate-tokens-lib file-id)
              tokens (ctob/get-tokens tokens-lib id)]
          (->> tokens
               (vals)
               (sort-by :name)
               (group-by #(cto/token-type->dtcg-token-type (:type %)))
               (into [])
               (mapv (fn [[type tokens]]
                       #js [(name type)
                            (->> tokens
                                 (map #(token-proxy plugin-id file-id id (:id %)))
                                 (apply array))]))
               (apply array))))}

     :getTokenById
     {:enumerable false
      :schema [:tuple ::sm/uuid]
      :fn (fn [token-id]
            (let [token (u/locate-token file-id id token-id)]
              (when (some? token)
                (token-proxy plugin-id file-id id token-id))))}

     :addToken
     {:enumerable false
      :schema (fn [args]
                (let [tokens-tree (-> (u/locate-tokens-lib file-id)
                                      (ctob/get-tokens id)
                                      ;; Convert to the adecuate format for schema
                                      (ctob/tokens-tree))]
                  [:tuple (-> (cfo/make-token-schema
                               tokens-tree
                               (cto/dtcg-token-type->token-type (-> args (first) (get "type")))
                               nil)
                              ;; Don't allow plugins to set the id
                              (sm/dissoc-key :id)
                              ;; Instruct the json decoder in obj/reify not to process map keys (:key-fn below)
                              ;; and set a converter that changes DTCG types to internal types (:decode/json).
                              ;; E.g. "FontFamilies" -> :font-family or "BorderWidth" -> :stroke-width
                              (sm/update-properties assoc :decode/json cfo/convert-dtcg-token))]))
      :decode/options {:key-fn identity}
      :fn (fn [attrs]
            (let [tokens-lib (u/locate-tokens-lib file-id)
                  token (ctob/make-token attrs)
                  ;; Resolve against all tokens in the library (including those
                  ;; in inactive sets) so that references to structurally
                  ;; existing tokens resolve even if their set is not active.
                  ;; The target set's tokens take precedence over equally named
                  ;; tokens in other sets, and the new token takes precedence
                  ;; over all.
                  tokens-tree (-> (merge (ctob/get-all-tokens-map tokens-lib)
                                         (ctob/get-tokens tokens-lib id))
                                  (assoc (:name token) token))
                  resolved-tokens (ts/resolve-tokens tokens-tree)

                  {:keys [errors resolved-value] :as resolved-token}
                  (get resolved-tokens (:name token))]

              (if resolved-value
                (do (st/emit! (-> (dwtl/create-token id token)
                                  (se/add-event plugin-id)))
                    (token-proxy plugin-id file-id id (:id token)))
                (do (u/not-valid plugin-id :addToken (str errors))
                    nil))))}

     :duplicate
     (fn []
       (let [id-ref (atom nil)]
         (st/emit! (dwtl/duplicate-token-set id {:id-ref id-ref}))
         (when (some? @id-ref)
           (token-set-proxy plugin-id file-id @id-ref))))

     :remove
     (fn []
       (st/emit! (dwtl/delete-token-set id))))))

(defn token-theme-proxy? [p]
  (obj/type-of? p "TokenThemeProxy"))

(defn- resolve-token-set
  "Resolves an addSet/removeSet argument to a token set. A proxy is returned
   as-is; an id is located in the file's token library."
  [file-id set-arg]
  (if (token-set-proxy? set-arg)
    set-arg
    (u/locate-token-set file-id set-arg)))

(defn- token-set-name
  "Reads the name from a resolved token set, supporting both proxies (whose
   getter falls back to the freshly-created name) and located sets."
  [set]
  (when (some? set)
    (if (token-set-proxy? set)
      (obj/get set "name")
      (ctob/get-name set))))

(defn token-theme-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenThemeProxy"
              :on-error (u/handle-error plugin-id)}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$id {:enumerable false :get (constantly id)}

    :id
    {:get #(dm/str id)}

    :external-id
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:external-id theme)))}

    :group
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:group theme)))
     :schema (let [theme (u/locate-token-theme file-id id)]
               (cfo/make-token-theme-group-schema
                (u/locate-tokens-lib file-id)
                (:name theme)
                (:id theme)))
     :set
     (fn [_ group]
       (let [theme (u/locate-token-theme file-id id)]
         (st/emit! (dwtl/update-token-theme id (assoc theme :group group)))))}

    :name
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:name theme)))
     :schema (let [theme (u/locate-token-theme file-id id)]
               (cfo/make-token-theme-name-schema
                (u/locate-tokens-lib file-id)
                (:id theme)
                (:group theme)))
     :set
     (fn [_ name]
       (let [theme (u/locate-token-theme file-id id)]
         (when name
           (st/emit! (dwtl/update-token-theme id (assoc theme :name name))))))}

    :active
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)]
         (ctob/theme-active? tokens-lib id)))
     :schema ::sm/boolean
     :set
     (fn [_ value]
       (st/emit! (dwtl/set-token-theme-active id value)))}

    :toggleActive
    (fn [_]
      (st/emit! (dwtl/toggle-token-theme-active id)))

    :activeSets
    {:this true
     :get (fn [_]
            (let [tokens-lib (u/locate-tokens-lib file-id)
                  theme (u/locate-token-theme file-id id)]
              (->> theme
                   :sets
                   (map #(->> %
                              (ctob/get-set-by-name tokens-lib)
                              (ctob/get-id)
                              (token-set-proxy plugin-id file-id)))
                   (apply array))))}

    :addSet
    {:enumerable false
     :schema [:tuple [:or [:fn token-set-proxy?] ::sm/uuid]]
     :fn (fn [set-arg]
           (let [set-name (token-set-name (resolve-token-set file-id set-arg))
                 theme    (u/locate-token-theme file-id id)]
             (when (and set-name theme)
               (st/emit! (dwtl/update-token-theme id (ctob/enable-set theme set-name))))))}

    :removeSet
    {:enumerable false
     :schema [:tuple [:or [:fn token-set-proxy?] ::sm/uuid]]
     :fn (fn [set-arg]
           (let [set-name (token-set-name (resolve-token-set file-id set-arg))
                 theme    (u/locate-token-theme file-id id)]
             (when (and set-name theme)
               (st/emit! (dwtl/update-token-theme id (ctob/disable-set theme set-name))))))}

    :duplicate
    (fn []
      (let [theme  (u/locate-token-theme file-id id)
            theme' (ctob/make-token-theme (-> (datafy theme)
                                              (dissoc :id
                                                      :modified-at)))]
        (st/emit! (dwtl/create-token-theme theme'))
        (token-theme-proxy plugin-id file-id (:id theme'))))

    :remove
    (fn []
      (st/emit! (dwtl/delete-token-theme id)))))

(defn tokens-catalog
  [plugin-id file-id]
  (obj/reify {:name "TokensCatalog"
              :on-error (u/handle-error plugin-id)}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly file-id)}

    :themes
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             themes (when tokens-lib
                      (->> (ctob/get-themes tokens-lib)
                           (remove #(= (:id %) uuid/zero))))]
         (apply array (map #(token-theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

    :sets
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             sets (when tokens-lib
                    (ctob/get-sets tokens-lib))]
         (apply array (map #(token-set-proxy plugin-id file-id (ctob/get-id %)) sets))))}

    :addTheme
    {:enumerable false
     :schema (fn [args]
               [:tuple (-> (cfo/make-token-theme-schema
                            (u/locate-tokens-lib file-id)
                            (get (first args) :group "")
                            (get (first args) :name "")
                            nil)
                           (sm/dissoc-key :id))]) ;; We don't allow plugins to set the id
     :fn (fn [attrs]
           (let [theme (ctob/make-token-theme attrs)]
             (st/emit! (dwtl/create-token-theme theme))
             (token-theme-proxy plugin-id file-id (:id theme))))}

    :addSet
    {:enumerable false
     :schema [:tuple (-> (sm/schema (cfo/make-token-set-schema
                                     (u/locate-tokens-lib file-id)
                                     nil))
                         (sm/dissoc-key :id) ;; We don't allow plugins to set the id
                         ;; Allow an optional `active` flag so a plugin can create
                         ;; an already-active set in a single call. Newly created
                         ;; sets are inactive by default (only active sets affect
                         ;; shapes and reference resolution). `active` is not part
                         ;; of the token-set data model, so the :fn strips it and
                         ;; applies it through the set-activation logic.
                         (sm/merge [:map [:active {:optional true} ::sm/boolean]]))]

     :fn (fn [attrs]
           (let [active? (boolean (:active attrs))
                 attrs   (-> attrs
                             (dissoc :active)
                             (update :name ctob/normalize-set-name))
                 set     (ctob/make-token-set attrs)]
             (st/emit! (dwtl/create-token-set set))
             ;; Newly created sets are inactive by default; activate it when
             ;; requested. Enabling only adds the set name to the hidden theme,
             ;; so it does not depend on the create event having propagated yet.
             (when active?
               (st/emit! (dwtl/set-enabled-token-set (ctob/get-name set) true)))
             ;; Pass the set name as `initial-name` so the proxy can resolve
             ;; it immediately, before the async `st/emit!` above propagates
             ;; the new set into `@st/state`.
             (token-set-proxy plugin-id file-id (ctob/get-id set) (ctob/get-name set))))}

    :getThemeById
    {:enumerable false
     :schema [:tuple ::sm/uuid]
     :fn (fn [theme-id]
           (let [theme (u/locate-token-theme file-id theme-id)]
             (when (some? theme)
               (token-theme-proxy plugin-id file-id theme-id))))}

    :getSetById
    {:enumerable false
     :schema [:tuple ::sm/uuid]
     :fn (fn [set-id]
           (let [set (u/locate-token-set file-id set-id)]
             (when (some? set)
               (token-set-proxy plugin-id file-id set-id))))}))
