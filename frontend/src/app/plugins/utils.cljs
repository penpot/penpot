;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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

(defn- error-path-segment->str
  "Stringify one segment of a schema-explain path.

  Keywords lose the leading colon, ints are kept verbatim (so tuple positions
  read as ``\"applyToken.0\"`` rather than crashing through ``name``), and
  anything else falls through ``str``."
  [seg]
  (cond
    (keyword? seg) (name seg)
    (string? seg)  seg
    :else          (str seg)))

(defn- error-path->label
  "Render the dotted field path that the user sees in the error message.

  Empty paths fall back to ``\"value\"`` so the formatted message never reads
  ``\"Field  is invalid: …\"`` when the schema rejects the whole input."
  [path]
  (if (seq path)
    (str/join "." (map error-path-segment->str path))
    "value"))

(defn collect-schema-error-leaves
  "Walk the nested error map produced by ``csm/interpret-schema-problem`` and
  emit a sequence of ``[field-path message]`` pairs, one per leaf.

  Each leaf is the ``{:message <string>}`` map that the reducer assoc-ins under
  the offending field path. Returning the *full* path (rather than just the
  innermost key, as the prior implementation did) keeps the eventual user
  message anchored to the property the plugin actually called — for example
  ``applyToken.1`` instead of the unhelpful ``message``."
  ([m] (collect-schema-error-leaves [] m))
  ([path m]
   (cond
     (and (map? m)
          (contains? m :message)
          (== 1 (count m)))
     [[path (:message m)]]

     (map? m)
     (mapcat (fn [[k v]] (collect-schema-error-leaves (conj path k) v)) m)

     :else
     [])))

(defn error-messages
  "Render an ``::sm/explain`` map into a list of human-readable plugin errors.

  The previous implementation iterated entries of the inner ``{:message …}``
  map and destructured each value, which silently produced a literal
  ``\"Field message is invalid: \"`` string for any single-level schema failure
  (issue #9290). The walker now collects every leaf with its full field path so
  the formatted line names the offending property and the message body is the
  schema's own explanation."
  [explain]
  (->> (:errors explain)
       (reduce csm/interpret-schema-problem {})
       (collect-schema-error-leaves)
       (map (fn [[path message]]
              (tr "plugins.validation.message"
                  (error-path->label path)
                  (or message ""))))
       (str/join ". ")))

(defn handle-error
  "Function to be used in plugin proxies methods to handle errors and print a readable
   message to the console."
  [plugin-id]
  (fn [cause]
    (let [message
          (if-let [explain (-> cause ex-data ::sm/explain)]
            (do
              (js/console.error (sm/humanize-explain explain))
              (error-messages explain))
            (ex-data cause))]
      (js/console.log (.-stack cause))
      (not-valid plugin-id :error message))))

(defn is-main-component-proxy?
  [p]
  (when-let [shape (proxy->shape p)]
    (ctk/main-instance? shape)))
