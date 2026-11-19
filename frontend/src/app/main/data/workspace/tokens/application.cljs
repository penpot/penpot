;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.application
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cfo]
   [app.common.logic.shapes :as cls]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.logic.tokenscript :as cts]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence.lock :as plk]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.reflow.signals :as wrfs]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwtr]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

(declare token-properties)
(declare all-attrs-appliable-for-token?)

;; Resolution -----------------------------------------------------------------------------------------------------------

(defn base-font-size
  "Pixels of one rem in the file of `state`."
  [state]
  (d/parse-double (ctf/get-base-font-size (dsh/lookup-file-data state)) 16))

(defn active-tokens
  "The active token sets of `state`, `[[set-id tokens] ..]` (see
  `cfo/get-active-token-sets`)."
  [state]
  (let [tokens-lib    (dsh/lookup-tokens-lib state)
        tokens-status (dsh/lookup-tokens-status state)]
    (when (and tokens-lib tokens-status)
      (cfo/get-active-token-sets tokens-status tokens-lib))))

(defn resolve-sets
  "Observable of the tokens of `sets`, `[[set-id tokens] ..]` with later
  sets taking precedence (see `cfo/get-active-token-sets`), with their
  resolved values. Rem values take `base-font-size` pixels each."
  [sets base-font-size]
  (if (contains? cf/flags :tokenscript)
    (rx/of (binding [cts/*base-font-size* base-font-size]
             (ts/resolve-sets sets)))
    (sd/resolve-tokens (transduce (map second) merge (d/ordered-map) sets))))

(defn lazy?
  "True when a change of the tokens only sets the current page."
  []
  (contains? cf/flags :lazy-token-propagation))

;; Committing token values ----------------------------------------------------------------------------------------------

(defn geometry-mode
  "How the token values of `page-id` set the geometry: with the renderer
  on the current page, when the lazy propagation leaves the other pages
  out, and with the CLJS modifiers otherwise."
  [state page-id]
  (if (and (lazy?)
           (features/active-feature? state "render-wasm/v1")
           (= page-id (:current-page-id state)))
    :wasm
    :cljs))

(defn- geometry-step
  "`apply-wasm-modifiers` of the modifier tree `tree`, or nil when it is empty.
  It follows token values already committed, so it goes through while saves
  fail."
  [tree save-undo?]
  (when (seq tree)
    (dwm/apply-wasm-modifiers tree
                              :ignore-touched true
                              :ignore-snap-pixel true
                              :ignore-save-lock true
                              :derived? true
                              :save-undo? save-undo?
                              ;; The tokens set the copies themselves
                              :skip-component-sync? true
                              :undo-transation? false)))

(defn- apply-geometry-step
  "Apply with the renderer the modifiers that `build` makes from the
  current shapes of `page-id`."
  [page-id build save-undo?]
  (ptk/reify ::apply-geometry-step
    ptk/WatchEvent
    (watch [_ state _]
      (some-> (build (dsh/lookup-page-objects state page-id))
              (geometry-step save-undo?)
              (rx/of)))))

(defn- text-size-tree
  "Modifiers that resize the texts `text-ids` of `objects` to their content,
  as the renderer measures it, but for the shapes already in `tree`."
  [tree objects text-ids]
  (reduce (fn [tree id]
            (let [shape (get objects id)]
              (if (and (some? shape)
                       (not (contains? tree id))
                       (cfh/text-shape? shape)
                       (not= :fixed (:grow-type shape)))
                (merge tree (dwwt/resize-wasm-text-modifiers shape))
                tree)))
          tree
          text-ids))

(def ^:private fonts-retry-ms 20)

(defn- apply-size-step
  "Apply with the renderer, in one pass, the new sizes and layouts left by
  the token values and the sizes of the texts `text-ids` for their new
  content. Waits until the fonts of those texts are loaded."
  [page-id {:keys [updates layout-ids]} text-ids save-undo?]
  (ptk/reify ::apply-size-step
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state page-id)
            step    (if (every? #(dwwt/content-fonts-ready? (dm/get-in objects [% :content])) text-ids)
                      (some-> (cltp/size-modif-tree objects updates layout-ids)
                              (text-size-tree objects text-ids)
                              (geometry-step save-undo?)
                              (rx/of))
                      (->> (rx/of (apply-size-step page-id {:updates updates :layout-ids layout-ids}
                                                   text-ids save-undo?))
                           (rx/delay fonts-retry-ms)))]
        ;; Holds the texts pending across the retries, until their size is set
        (if (seq text-ids)
          (wrf/with-pending :text-resize text-ids (or step (rx/empty)))
          step)))))

(defn- apply-pending-geometry
  "Events that apply with the renderer the rotations, positions, sizes and
  layouts left by the token values, one after another, with the sizes of
  the texts `text-ids` in the last step."
  [page-id {:keys [updates] :as geometry} text-ids save-undo?]
  (rx/of (apply-geometry-step page-id #(cltp/rotation-modif-tree % updates) save-undo?)
         (apply-geometry-step page-id #(cltp/position-modif-tree % updates) save-undo?)
         (apply-size-step page-id geometry text-ids save-undo?)))

(defn- changed-shape-ids
  [changes]
  (into []
        (comp (filter #(= :mod-obj (:type %)))
              (map :id)
              (distinct))
        (:redo-changes changes)))

(defn- content-changed-ids
  "Ids of the shapes whose content `changes` set."
  [changes]
  (into #{}
        (comp (filter #(= :mod-obj (:type %)))
              (filter (fn [{:keys [operations]}]
                        (some #(and (= :set (:type %)) (= :content (:attr %))) operations)))
              (map :id))
        (:redo-changes changes)))

(defn commit-token-values
  "Events that commit `changes`, with the new token values of the shapes of
  `page-id`, and announce the texts of the current page that the renderer
  has to measure again. `objects` are the shapes before the changes and
  `targets` the token attributes that were set.

  `derived?` when `changes` hold only values that follow tokens already
  committed: they go through while saves fail. Otherwise they carry an edit
  of the user, refused then with the steps that would follow it."
  [state changes page-id objects targets derived?]
  (if (or (empty? (:redo-changes changes))
          (and (not derived?) (plk/locked? state)))
    (rx/empty)
    (let [current?   (= page-id (:current-page-id state))
          wasm?      (features/active-feature? state "render-wasm/v1")
          resize-ids
          (when (and current? wasm?)
            (seq (filter (content-changed-ids changes)
                         (cltp/text-ids-to-resize objects targets))))

          ;; The renderer measures again only the texts it resizes
          text-ids
          (when current?
            (seq (cond->> (:text-ids (wrfs/reflow-ids-of-changes state page-id objects changes
                                                                 (changed-shape-ids changes) nil))
                   wasm? (filter (set resize-ids)))))

          save-undo? (:save-undo? changes true)
          geometry   (cltp/pending-geometry changes)
          ;; The texts are resized in the pass of the new sizes
          one-pass?  (= :wasm (geometry-mode state page-id))]
      (rx/concat
       (if text-ids
         (rx/of (ptk/data-event :text/reflow {:ids text-ids :page-id page-id}))
         (rx/empty))
       (rx/of (dch/commit-changes (cond-> changes derived? (assoc :ignore-save-lock true))))
       (cond
         (some? geometry)
         (apply-pending-geometry page-id geometry (when one-pass? (vec resize-ids)) save-undo?)

         (and one-pass? resize-ids)
         (rx/of (apply-size-step page-id nil (vec resize-ids) save-undo?))

         :else
         (rx/empty))
       (if (and resize-ids (not one-pass?))
         (rx/of (dwwt/resize-wasm-text-all (vec resize-ids)
                                           {:skip-component-sync? true
                                            :save-undo? save-undo?
                                            :ignore-save-lock true}))
         (rx/empty))))))

;; Events to apply / unapply tokens to shapes ------------------------------------------------------------

(defn warn-font-variant-not-found
  []
  (ntf/show {:content (tr "workspace.tokens.font-variant-not-found")
             :type :toast
             :level :warning
             :timeout 7000}))

(defn- set-applied-token
  "Apply the token to the shapes and set them to its value, in
  `resolved-tokens`. Reads the shapes when it runs, after the tokens are
  resolved, so it builds on the previous applications."
  [{:keys [attributes attributes-to-remove token shape-ids prepare-shape events resolved-tokens]}]
  (ptk/reify ::set-applied-token
    ptk/WatchEvent
    (watch [it state _]
      (let [undo-id (js/Symbol)
            page    (dsh/lookup-page state)
            objects (:objects page)
            selected-shapes (select-keys objects shape-ids)

            shapes (->> selected-shapes
                        (filter (fn [[_ shape]]
                                  (or
                                   (and (ctsl/any-layout-immediate-child? objects shape)
                                        (some ctt/spacing-margin-keys attributes))
                                   (and (ctt/any-appliable-attr-for-shape? attributes (:type shape) (:layout shape))
                                        (all-attrs-appliable-for-token? attributes (:type token)))))))
            shape-ids (d/nilv (keys shapes) [])
            any-variant? (->> shapes vals (some ctk/is-variant?) boolean)
            file-data    (dsh/lookup-file-data state)
            from-library (not (cfo/effective-tokens-source? file-data (:id file-data)))
            tokenized-attributes (cfo/attributes-map attributes token)
            type (:type token)

            changes
            (-> (pcb/empty-changes it (:id page))
                (pcb/with-page page)
                (cls/generate-update-shapes
                 shape-ids
                 (fn [shape]
                   (cond-> shape
                     attributes-to-remove
                     (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                     :always
                     (update :applied-tokens merge tokenized-attributes)
                     (some? prepare-shape)
                     (prepare-shape attributes)))
                 objects
                 {}))

            mismatch? (volatile! false)
            targets   (zipmap shape-ids (repeat attributes))
            changes   (cltp/generate-token-propagation
                       changes
                       (pcb/lookup-objects changes)
                       resolved-tokens
                       targets
                       {:fonts @fonts/fontsdb
                        :geometry (geometry-mode state (:id page))
                        :on-variant-mismatch #(vreset! mismatch? true)})]
        (rx/concat
         (rx/of
          (ev/event
           (-> {::ev/name "apply-tokens"
                :type type
                :applied-to attributes
                :applied-to-variant any-variant?
                :shape-count (count shape-ids)
                :from-library from-library}
               (merge (meta it))))
          (dwu/start-undo-transaction undo-id))
         (commit-token-values state changes (:id page) objects targets false)
         (when @mismatch?
           (rx/of (warn-font-variant-not-found)))
         (when (= type :text-decoration)
           (rx/of (ptk/data-event :expand-text-more-options)))
         (when (and (contains? attributes :rotation) (seq shape-ids))
           (rx/of (dwtr/trigger-bounding-box-cloaking (vec shape-ids))))
         (when (seq events)
           (rx/from events))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))

(defn apply-token
  "Apply `attributes` that match `token` for `shape-ids`, and set them to
  its value.

  Optionally remove attributes from `attributes-to-remove`,
  this is useful for applying a single attribute from an attributes set
  while removing other applied tokens from this set.

  `prepare-shape`, a function of a shape and the attributes, changes the
  shapes together with the application. `events` are emitted after it."
  [{:keys [attributes-to-remove token] :as params}]
  (assert (ctob/token? token) "apply-token event requires a valid token")
  (ptk/reify ::apply-token
    ptk/WatchEvent
    (watch [it state _]
      ;; We do not allow to apply tokens while text editor is open.
      (let [edition       (get-in state [:workspace-local :edition])
            objects       (dsh/lookup-page-objects state)
            text-editing? (and (some? edition)
                               (= :text (:type (get objects edition))))]
        (if (and (some? token)
                 (not text-editing?))
          (let [attributes-to-remove
                ;; Remove atomic typography tokens when applying composite and vice-versa
                (cond
                  (ctt/typography-token-keys (:type token)) (set/union attributes-to-remove ctt/typography-keys)
                  (ctt/typography-keys (:type token)) (set/union attributes-to-remove ctt/typography-token-keys)
                  :else attributes-to-remove)]
            (when-let [sets (active-tokens state)]
              (->> (resolve-sets sets (base-font-size state))
                   (rx/map (fn [resolved-tokens]
                             (-> (assoc params
                                        :attributes-to-remove attributes-to-remove
                                        :resolved-tokens resolved-tokens)
                                 (set-applied-token)
                                 (with-meta (meta it))))))))

          (when text-editing?
            (rx/of (ntf/show {:content (tr "workspace.tokens.error-text-edition")
                              :type :toast
                              :level :warning
                              :timeout 3000}))))))))

(defn apply-spacing-token-separated
  "Handles edge-case for spacing token when applying token via toggle button.
  Splits out `shape-ids` into separate default actions:
  - Layouts take the `default` update function
  - Shapes inside layout will only take margin"
  [{:keys [token shapes]}]
  (ptk/reify ::apply-spacing-token-separated
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (dsh/lookup-page-objects state)

            {:keys [attributes]}
            (get token-properties (:type token))

            {:keys [other frame-children]}
            (group-by #(if (ctsl/any-layout-immediate-child? objects %) :frame-children :other) shapes)]

        (rx/of
         (-> (apply-token {:attributes attributes
                           :token token
                           :shape-ids (map :id other)})
             (with-meta (meta it)))

         (-> (apply-token {:attributes ctt/spacing-margin-keys
                           :token token
                           :shape-ids (map :id frame-children)})
             (with-meta (meta it))))))))

(defn unapply-token
  "Removes `attributes` that match `token` for `shape-ids`.

  Doesn't update shape attributes."
  [{:keys [attributes token-name shape-ids] :as _props}]
  (ptk/reify ::unapply-token
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (let [remove-token #(when % (cfo/remove-attributes-for-token attributes token-name %))]
         (dwsh/update-shapes
          shape-ids
          (fn [shape]
            (update shape :applied-tokens remove-token))))))))

(defn unapply-multiple-tokens
  "Removes `attributes` for `shape-ids` without knowing the token, used when a token is deleted."
  [{:keys [attributes shape-ids] :as _props}]

  (ptk/reify ::unapply-multiple-tokens
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (dwsh/update-shapes
        shape-ids
        (fn [shape]
          (update shape :applied-tokens #(when % (apply dissoc % attributes)))))))))


(defn- target-shapes
  "The selected shapes a token goes on. A selected group can stand for
  its children, since groups take no tokens."
  [objects shape-ids expand-with-children]
  (cond->> (into [] (keep (d/getf objects)) shape-ids)
    expand-with-children
    (into [] (mapcat (fn [shape]
                       (if (= (:type shape) :group)
                         (keep objects (:shapes shape))
                         [shape]))))))

(defn- apply-action
  "Works out which attributes the token goes on: the chosen ones, or its
  type's defaults. A spacing token without chosen attributes goes on
  layouts, and only on the margins of the shapes inside them."
  [token attrs]
  (let [{:keys [attributes]} (get token-properties (:type token))]
    (if (and (= (:type token) :spacing) (nil? attrs))
      {:action :apply-spacing}
      {:action :apply
       :attributes (if (empty? attrs) attributes attrs)})))

(defn- toggle-action
  "Works out whether toggling the token removes it from the selection
  or applies it, and on which attributes. From the token pill it comes
  off when any shape has it; on chosen attributes (a design tab input,
  a plugin) only when every shape has it on all of them, so a partial
  application is completed instead."
  [token attrs shapes]
  (let [{:keys [attributes all-attributes]} (get token-properties (:type token))
        target-attrs (or attrs all-attributes attributes)
        unapply?     (if (seq attrs)
                       (cfo/shapes-applied-all?
                        (cfo/shapes-ids-by-applied-attributes token shapes target-attrs)
                        (into #{} (map :id) shapes)
                        target-attrs)
                       (cfo/shapes-token-applied? token shapes target-attrs))]
    (if unapply?
      {:action :unapply :attributes target-attrs}
      (apply-action token attrs))))

(defn- action->event
  [{:keys [action attributes]} token shapes shape-ids]
  (case action
    :unapply
    (unapply-token {:attributes attributes
                    :token-name (:name token)
                    :shape-ids shape-ids})

    :apply-spacing
    (apply-spacing-token-separated {:token token
                                    :shapes shapes})

    :apply
    (apply-token {:attributes attributes
                  :token token
                  :shape-ids shape-ids})))

(defn toggle-token
  [{:keys [token attrs shape-ids expand-with-children]}]
  (ptk/reify ::on-toggle-token
    ptk/WatchEvent
    (watch [it state _]
      (let [shapes (target-shapes (dsh/lookup-page-objects state) shape-ids expand-with-children)
            action (toggle-action token attrs shapes)
            event  (action->event action token shapes (map :id shapes))]
        (rx/of
         (if (= :unapply (:action action))
           event
           (with-meta event (meta it))))))))

(defn apply-token-from-input
  [{:keys [token attrs shape-ids expand-with-children]}]
  (ptk/reify ::apply-token-from-input
    ptk/WatchEvent
    (watch [_ state _]
      (let [shapes (target-shapes (dsh/lookup-page-objects state) shape-ids expand-with-children)]
        (rx/of
         (action->event (apply-action token attrs) token shapes shape-ids))))))


(defn apply-token-on-color-selected
  [color-operations token]
  (ptk/reify ::apply-token-on-color-selected
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-id (js/Symbol)]
        (rx/concat
         (rx/of (dwu/start-undo-transaction undo-id))
         (->> (rx/from color-operations)
              (rx/map
               (fn [cop]
                 (let [shape-ids [(:shape-id cop)]]
                   (case (:prop cop)
                     :fill    (apply-token {:attributes #{:fill}
                                            :token token
                                            :shape-ids shape-ids})
                     :stroke  (apply-token {:attributes #{:stroke-color}
                                            :token token
                                            :shape-ids shape-ids})
                     ;; Text
                     :content (apply-token {:attributes #{:fill}
                                            :token token
                                            :shape-ids shape-ids})
                     :shadow  (rx/empty))))))
         (rx/of (dwu/commit-undo-transaction undo-id)))))))
;; Map token types to different properties used along the code ---------------------------------------------

;; FIXME: the values should be lazy evaluated, probably a function,
;; becasue on future we will need to translate that labels and that
;; can not be done statically

(def token-properties
  "A map of default properties by token type"
  (d/ordered-map
   :border-radius
   {:title "Border Radius"
    :attributes ctt/border-radius-keys
    :modal {:key :tokens/border-radius
            :fields [{:label "Border Radius"
                      :key :border-radius}]}}

   :shadow
   {:title "Shadow"
    :attributes ctt/shadow-keys
    :modal {:key :tokens/shadow
            :fields [{:label "Shadow"
                      :key :shadow}]}}

   :color
   {:title "Color"
    :attributes #{:fill}
    :all-attributes ctt/color-keys
    :modal {:key :tokens/color
            :fields [{:label "Color" :key :color}]}}

   :font-size
   {:title "Font Size"
    :attributes ctt/font-size-keys
    :modal {:key :tokens/font-size
            :fields [{:label "Font Size"
                      :key :font-size}]}}

   :letter-spacing
   {:title "Letter Spacing"
    :attributes ctt/letter-spacing-keys
    :modal {:key :tokens/letter-spacing
            :fields [{:label "Letter Spacing"
                      :key :letter-spacing}]}}

   :font-family
   {:title "Font Family"
    :attributes ctt/font-family-keys
    :modal {:key :tokens/font-family
            :fields [{:label "Font Family"
                      :key :font-family}]}}

   :text-case
   {:title "Text Case"
    :attributes ctt/text-case-keys
    :modal {:key :tokens/text-case
            :fields [{:label "Text Case"
                      :key :text-case}]}}

   :font-weight
   {:title "Font Weight"
    :attributes ctt/font-weight-keys
    :modal {:key :tokens/font-weight
            :fields [{:label "Font Weight"
                      :key :font-weight}]}}

   :typography
   {:title "Typography"
    :attributes ctt/typography-token-keys
    :modal {:key :tokens/typography
            :fields [{:label "Typography"
                      :key :typography}]}}

   :text-decoration
   {:title "Text Decoration"
    :attributes ctt/text-decoration-keys
    :modal {:key :tokens/text-decoration
            :fields [{:label "Text Decoration"
                      :key :text-decoration}]}}

   :stroke-width
   {:title "Stroke Width"
    :attributes ctt/stroke-width-keys
    :modal {:key :tokens/stroke-width
            :fields [{:label "Stroke Width"
                      :key :stroke-width}]}}

   :sizing
   {:title "Sizing"
    :attributes #{:width :height}
    :all-attributes ctt/sizing-keys
    :modal {:key :tokens/sizing
            :fields [{:label "Sizing"
                      :key :sizing}]}}
   :dimensions
   {:title "Dimensions"
    :attributes #{:width :height}
    :all-attributes (set/union
                     ctt/spacing-keys
                     ctt/sizing-keys
                     ctt/border-radius-keys
                     ctt/axis-keys
                     ctt/stroke-width-keys)
    :modal {:key :tokens/dimensions
            :fields [{:label "Dimensions"
                      :key :dimensions}]}}

   :opacity
   {:title "Opacity"
    :attributes ctt/opacity-keys
    :modal {:key :tokens/opacity
            :fields [{:label "Opacity"
                      :key :opacity}]}}

   :number
   {:title "Number"
    :attributes ctt/rotation-keys
    :all-attributes ctt/number-keys
    :modal {:key :tokens/number
            :fields [{:label "Number"
                      :key :number}]}}

   :rotation
   {:title "Rotation"
    :attributes ctt/rotation-keys
    :modal {:key :tokens/rotation
            :fields [{:label "Rotation"
                      :key :rotation}]}}
   :spacing
   {:title "Spacing"
    :attributes #{:column-gap :row-gap}
    :all-attributes ctt/spacing-keys
    :modal {:key :tokens/spacing
            :fields [{:label "Spacing"
                      :key :spacing}]}}))

(defn get-token-properties [token]
  (get token-properties (:type token)))

(defn appliable-attributes-for-token
  "Get the attributes to which this token type can be applied."
  [token-type]
  (let [props (get token-properties token-type)]
    (or (:all-attributes props)
        (:attributes props))))

(defn all-attrs-appliable-for-token?
  "Check if any of the given attributes can be applied for the given token type."
  [attributes token-type]
  (set/subset? attributes (appliable-attributes-for-token token-type)))
