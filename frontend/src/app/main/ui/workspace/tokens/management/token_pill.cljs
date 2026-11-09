;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.management.token-pill
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.path-names :as cpn]
   [app.common.types.token :as ctt]
   [app.config :as cf]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.color :as dwtc]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.utilities.token.token-status :refer [token-status-icon*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Translation dictionaries

(def ^:private attribute-dictionary
  {:rotation "Rotation"
   :opacity "Opacity"
   :stroke-width "Stroke Width"

   ;; Spacing
   :p1 "Top" :p2 "Right" :p3 "Bottom" :p4 "Left"
   :column-gap "Column Gap" :row-gap "Row Gap"

   ;; Sizing
   :width "Width"
   :height "Height"
   :layout-item-min-w "Min Width"
   :layout-item-min-h "Min Height"
   :layout-item-max-w "Max Width"
   :layout-item-max-h "Max Height"

   ;; Border Radius
   :r1 "Top Left" :r2 "Top Right" :r4 "Bottom Left" :r3 "Bottom Right"

   ;; Dimensions
   :x "X" :y "Y"

   ;; Color
   :fill "Fill"
   :stroke-color "Stroke Color"

   ;; Stroke Width
   :stroke-width-top "Top"
   :stroke-width-right "Right"
   :stroke-width-bottom "Bottom"
   :stroke-width-left "Left"})

(def ^:private dimensions-dictionary
  {:stroke-width-top :stroke-width
   :stroke-width-right :stroke-width
   :stroke-width-bottom :stroke-width
   :stroke-width-left :stroke-width
   :p1 :spacing
   :p2 :spacing
   :p3 :spacing
   :p4 :spacing
   :column-gap :spacing
   :row-gap :spacing
   :width :sizing
   :height :sizing
   :layout-item-min-w :sizing
   :layout-item-min-h :sizing
   :layout-item-max-w :sizing
   :layout-item-max-h :sizing
   :r1 :border-radius
   :r2 :border-radius
   :r4 :border-radius
   :r3 :border-radius
   :x :x
   :y :y})

;; Helper functions

(defn partially-applied-attr
  "Translates partially applied attributes based on the dictionary."
  [app-token-keys is-applied {:keys [attributes all-attributes]}]
  (let [filtered-keys (if all-attributes
                        (filter #(contains? all-attributes %) app-token-keys)
                        (filter #(contains? attributes %) app-token-keys))]
    (when is-applied
      (str/join ", " (map attribute-dictionary filtered-keys)))))

(defn translate-and-format
  "Translates and formats grouped values by category."
  [grouped-values]
  (str/join "\n"
            (map (fn [[category values]]
                   (if (#{:x :y} category)
                     (dm/str "- " (dwtf/category-dictionary category))
                     (dm/str "- " (dwtf/category-dictionary category) ": "
                             (str/join ", " (map attribute-dictionary values)) ".")))
                 grouped-values)))

(defn- tooltip-info
  "For an invalid token the tooltip is only its error message. Otherwise
  it shows the token's name and values and, for viewers, the attributes
  of `shape` the token is applied to."
  [token resolved-token shape state is-viewer]
  (let [{:keys [name type value]} token
        {:keys [is-half-applied has-missing-reference has-invalid-value
                error-message]} state
        resolved-value (or (:resolved-value resolved-token) (:resolved-value token))
        {:keys [title] :as token-props} (dwta/get-token-properties resolved-token)
        applied-tokens (:applied-tokens shape)
        app-token-keys (keys applied-tokens)
        is-applied     (contains? (set (vals applied-tokens)) name)]

    (cond
      has-missing-reference
      {:message (tr "workspace.tokens.ref-not-valid" name)}

      error-message
      {:message error-message}

      has-invalid-value
      {:message (tr "workspace.tokens.value-not-valid")}

      :else
      {:name name
       :value (dwtf/format-token-value value)
       :resolved-value (dwtf/format-token-value resolved-value)
       :has-more-options (= type :number)
       :applied-to
       (when (and is-applied is-viewer)
         (if (= :dimensions type)
           (translate-and-format (group-by dimensions-dictionary app-token-keys))
           (str "- " title ": "
                (if is-half-applied
                  (partially-applied-attr app-token-keys is-applied token-props)
                  (tr "labels.all")))))})))

(defn- render-tooltip
  [{:keys [message name value resolved-value has-more-options applied-to]}]
  (or message
      (mf/html
       [:*
        (dm/str (tr "workspace.tokens.token-name") ": ")
        [:span {:class (stl/css :token-pill-tooltip-name)} name]
        [:div (dm/str (tr "workspace.tokens.original-value" value) "\n"
                      (tr "workspace.tokens.resolved-value" resolved-value)
                      (when has-more-options
                        (dm/str "\n" (tr "workspace.tokens.more-options"))))]
        (when applied-to
          [:*
           [:div (tr "workspace.tokens.applied-to")]
           [:div applied-to]])])))

;; FIXME: the token thould already have precalculated references, so
;; we don't need to perform this regex operation on each rerender
(defn contains-reference-value?
  "Extracts the value between `{}` in a string and checks if it's in the provided vector."
  [text active-tokens]
  (let [match (second (re-find #"\{([^}]+)\}" text))]
    (contains? active-tokens match)))

(def ^:private
  xf:map-id
  (map :id))

(defn- applied-all-attributes?
  [token selected-shapes attributes]
  (let [ids-by-attributes (cfo/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids         (into #{} xf:map-id selected-shapes)]
    (cfo/shapes-applied-all? ids-by-attributes shape-ids attributes)))

(defn attributes-match-selection?
  [selected-shapes attrs & {:keys [selected-inside-layout?]}]
  (or
   ;; Edge-case for allowing margin attribute on shapes inside layout parent
   (and selected-inside-layout? (set/subset? ctt/spacing-margin-keys attrs))
   (some (fn [shape]
           (ctt/any-appliable-attr-for-shape? attrs (:type shape) (:layout shape)))
         selected-shapes)))

(def token-types-with-status-icon
  #{:color :border-radius :rotation :sizing :dimensions :opacity :spacing :stroke-width})

(defn- pill-state
  "Works out whether the token is applied to the selection, partially
  applied, disabled or invalid. Without a selection it counts as fully
  applied."
  [token active-theme-tokens selected-shapes is-selected-inside-layout]
  (let [{:keys [name value type]} token
        errors       (:errors (get active-theme-tokens name))
        has-selected (seq selected-shapes)
        tokenscript  (contains? cf/flags :tokenscript)

        attributes
        (as-> (get dwta/token-properties type) $
          (d/nilv (:all-attributes $) (:attributes $)))

        full-applied
        (or (not has-selected)
            (applied-all-attributes? token selected-shapes attributes))

        applied
        (boolean (and has-selected
                      (cfo/shapes-token-applied? token selected-shapes attributes)))

        half-applied (and applied (not full-applied))

        missing-reference
        (boolean
         (if tokenscript
           (wte/has-error-code? :error.style-dictionary/missing-reference errors)
           (and (cfo/is-reference? token)
                (not (contains-reference-value? value active-theme-tokens)))))

        name-collision
        (= :error.token/name-collision (:error/code (first errors)))

        invalid-value (boolean (seq errors))]

    {:is-applied applied
     :is-half-applied half-applied
     :is-disabled
     (boolean
      (and has-selected
           (not applied)
           (not (attributes-match-selection?
                 selected-shapes attributes
                 {:selected-inside-layout? is-selected-inside-layout}))))
     :has-missing-reference missing-reference
     :has-invalid-value invalid-value
     ;; Tokenscript errors carry their own message, as in the edit form.
     :error-message
     (when (and invalid-value (or tokenscript name-collision))
       (wte/resolve-error-message (first errors)))
     :has-errors (or missing-reference invalid-value)
     :status-icon-id
     (cond
       half-applied "token-status-partial"
       full-applied "token-status-full"
       :else        "token-status-non-applied")}))

(mf/defc token-pill*
  {::mf/wrap [mf/memo]}
  [{:keys [on-click token on-context-menu selected-shapes is-selected-inside-layout active-theme-tokens can-edit]}]
  (let [{:keys [name type]} token
        resolved-token (get active-theme-tokens name)

        pill-ref       (mf/use-ref nil)
        pill-id        (mf/use-id)

        tooltip-content* (mf/use-state "")
        tooltip-content  (deref tooltip-content*)

        contains-path? (str/includes? name ".")
        is-viewer?     (not can-edit)

        {:keys [is-applied is-disabled has-invalid-value error-message
                has-errors status-icon-id]
         :as state}
        (mf/with-memo [token active-theme-tokens selected-shapes is-selected-inside-layout]
          (pill-state token active-theme-tokens selected-shapes is-selected-inside-layout))

        color
        (when (cfo/color-token? token)
          (or (dwtc/resolved-token-bullet-color resolved-token)
              (dwtc/resolved-token-bullet-color token)))

        status-icon? (contains? token-types-with-status-icon type)

        on-context-menu
        (mf/use-fn
         (mf/deps can-edit on-context-menu token)
         (fn [e]
           (dom/stop-propagation e)
           (when can-edit
             (on-context-menu e token))))

        on-click
        (mf/use-fn
         (mf/deps can-edit has-invalid-value on-click token)
         (fn [event]
           (dom/stop-propagation event)
           (when (and can-edit (not has-invalid-value) on-click)
             (on-click event token))))

        on-hover
        (mf/use-fn
         (mf/deps token resolved-token selected-shapes state is-viewer?)
         (fn [_]
           (->> (tooltip-info token resolved-token (first selected-shapes) state is-viewer?)
                (render-tooltip)
                (reset! tooltip-content*))))

        ;; `tooltip-content` may hold rich (hiccup) content, so it's wrapped
        ;; in a function to satisfy the tooltip's `:content` schema; this
        ;; wrapper is cheap, the actual (expensive) computation still only
        ;; happens once, inside `on-hover`.
        tooltip-content-fn
        (mf/use-fn
         (mf/deps tooltip-content)
         (fn [] tooltip-content))]

    [:> tooltip* {:content tooltip-content-fn
                  :trigger-ref pill-ref
                  :id pill-id}
     [:button {:ref pill-ref
               :class (stl/css-case
                       :token-pill true
                       :token-pill-no-icon (and (not status-icon?) (not has-errors))
                       :token-pill-default can-edit
                       :token-pill-disabled is-disabled
                       :token-pill-applied (and can-edit is-applied)
                       :token-pill-invalid (and can-edit has-errors)
                       :token-pill-invalid-applied (and is-applied has-errors can-edit)

                       :token-pill-viewer is-viewer?
                       :token-pill-applied-viewer (and is-viewer?
                                                       is-applied)
                       :token-pill-invalid-viewer (and is-viewer?
                                                       has-errors)
                       :token-pill-invalid-applied-viewer (and is-viewer?
                                                               is-applied))
               :id (str "token-pill-" (:id token))
               :type "button"
               :on-focus on-hover

               :on-click on-click
               :on-mouse-enter on-hover

               :on-context-menu on-context-menu}
      (cond
        has-errors
        [:> icon*
         {:icon-id i/broken-link
          :class (stl/css :token-pill-icon)
          :aria-label (or error-message
                          (tr "workspace.tokens.missing-reference"))}]

        color
        [:> swatch* {:background color
                     :size "small"}]

        status-icon?
        [:> token-status-icon*
         {:icon-id status-icon-id
          :class (stl/css :token-pill-icon)}])

      (if contains-path?
        (let [[_ last-part] (cpn/split-by-last-period name)]
          [:span {:class (stl/css :divided-name-wrapper)
                  :aria-label name}
           [:span {:class (stl/css :last-name-wrapper)} last-part]])
        [:span {:class (stl/css :name-wrapper)
                :aria-label name}
         name])]]))
