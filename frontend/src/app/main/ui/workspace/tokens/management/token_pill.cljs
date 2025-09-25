;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.token-pill
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cft]
   [app.common.types.token :as ctt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.color :as dwtc]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.utilities.token.token-status :refer [token-status-icon*]]
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
   :stroke-color "Stroke Color"})

(def ^:private dimensions-dictionary
  {:stroke-width :stroke-width
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

(def ^:private category-dictionary
  {:stroke-width "Stroke Width"
   :spacing "Spacing"
   :sizing "Sizing"
   :border-radius "Border Radius"
   :x "X"
   :y "Y"
   :font-size "Font Size"
   :font-family "Font Family"
   :font-weight "Font Weight"
   :letter-spacing "Letter Spacing"
   :text-case "Text Case"
   :text-decoration "Text Decoration"})

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
                     (dm/str "- " (category-dictionary category))
                     (dm/str "- " (category-dictionary category) ": "
                             (str/join ", " (map attribute-dictionary values)) ".")))
                 grouped-values)))

(defn format-token-value [token-value]
  (cond
    (map? token-value)
    (->> (map (fn [[k v]] (str "- " (category-dictionary k) ": " (format-token-value v))) token-value)
         (str/join "\n")
         (str "\n"))

    (sequential? token-value)
    (str/join "," token-value)

    :else
    (str token-value)))

(defn- generate-tooltip
  "Generates a tooltip for a given token"
  [is-viewer shape theme-token token half-applied no-valid-value ref-not-in-active-set]
  (let [{:keys [name type resolved-value value]} token
        resolved-value-theme (:resolved-value theme-token)
        resolved-value (or resolved-value-theme resolved-value)
        {:keys [title] :as token-props} (dwta/get-token-properties theme-token)
        applied-tokens (:applied-tokens shape)
        app-token-vals (set (vals applied-tokens))
        app-token-keys (keys applied-tokens)
        is-applied? (contains? app-token-vals name)


        applied-to (if half-applied
                     (partially-applied-attr app-token-keys is-applied? token-props)
                     (tr "labels.all"))
        grouped-values (group-by dimensions-dictionary app-token-keys)

        base-title (dm/str "Token: " name "\n"
                           (tr "workspace.tokens.original-value" (format-token-value value)) "\n"
                           (tr "workspace.tokens.resolved-value" (format-token-value resolved-value))
                           (when (= (:type token) :number)
                             (dm/str "\n" (tr "workspace.tokens.more-options"))))]

    (cond
      ;; If there are errors, show the appropriate message
      ref-not-in-active-set
      (tr "workspace.tokens.ref-not-valid")

      no-valid-value
      (tr "workspace.tokens.value-not-valid")

      ;; If the token is applied and the user is a is-viewer, show the details
      (and is-applied? is-viewer)
      (->> [base-title
            (tr "workspace.tokens.applied-to")
            (if (= :dimensions type)
              (translate-and-format grouped-values)
              (str "- " title ": " applied-to))]
           (str/join "\n"))

      ;; Otherwise only show the base title
      :else base-title)))

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
  (let [ids-by-attributes (cft/shapes-ids-by-applied-attributes token selected-shapes attributes)
        shape-ids         (into #{} xf:map-id selected-shapes)]
    (cft/shapes-applied-all? ids-by-attributes shape-ids attributes)))

(defn attributes-match-selection?
  [selected-shapes attrs & {:keys [selected-inside-layout?]}]
  (or
   ;; Edge-case for allowing margin attribute on shapes inside layout parent
   (and selected-inside-layout? (set/subset? ctt/spacing-margin-keys attrs))
   (some (fn [shape]
           (ctt/any-appliable-attr? attrs (:type shape) (:layout shape)))
         selected-shapes)))

(def token-types-with-status-icon
  #{:color :border-radius :rotation :sizing :dimensions :opacity :spacing :stroke-width})

(mf/defc token-pill*
  {::mf/wrap [mf/memo]}
  [{:keys [on-click token on-context-menu selected-shapes is-selected-inside-layout active-theme-tokens]}]
  (let [{:keys [name value errors type]} token

        has-selected?  (pos? (count selected-shapes))
        is-reference?  (cft/is-reference? token)
        contains-path? (str/includes? name ".")

        attributes (as-> (get dwta/token-properties type) $
                     (d/nilv (:all-attributes $) (:attributes $)))

        full-applied?
        (if has-selected?
          (applied-all-attributes? token selected-shapes attributes)
          true)

        applied?
        (if has-selected?
          (cft/shapes-token-applied? token selected-shapes attributes)
          false)

        half-applied?
        (and applied? (not full-applied?))

        disabled? (and
                   has-selected?
                   (not applied?)
                   (not half-applied?)
                   (not (attributes-match-selection? selected-shapes attributes {:selected-inside-layout? is-selected-inside-layout})))

        ;; FIXME: move to context or props
        can-edit? (:can-edit (deref refs/permissions))

        is-viewer? (not can-edit?)

        ref-not-in-active-set
        (and is-reference?
             (not (contains-reference-value? value active-theme-tokens)))

        no-valid-value (seq errors)

        errors?
        (or ref-not-in-active-set
            no-valid-value)

        color
        (when (cft/color-token? token)
          (let [theme-token (get active-theme-tokens name)]
            (or (dwtc/resolved-token-bullet-color theme-token)
                (dwtc/resolved-token-bullet-color token))))

        status-icon? (contains? token-types-with-status-icon type)

        on-click
        (mf/use-fn
         (mf/deps errors? on-click token)
         (fn [event]
           (dom/stop-propagation event)
           (when (and (not (seq errors)) on-click)
             (on-click event token))))

        token-status-id
        (cond
          half-applied?
          "token-status-partial"
          full-applied?
          "token-status-full"
          :else
          "token-status-non-applied")

        on-context-menu
        (mf/use-fn
         (mf/deps can-edit? on-context-menu token)
         (fn [e]
           (dom/stop-propagation e)
           (when can-edit?
             (on-context-menu e token))))

        on-click
        (mf/use-fn
         (mf/deps errors? on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (and can-edit? (not (seq errors)) on-click)
             (on-click event))))

        on-hover
        (mf/use-fn
         (mf/deps selected-shapes is-viewer? active-theme-tokens token half-applied? no-valid-value ref-not-in-active-set)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 theme-token (get active-theme-tokens name)
                 title (generate-tooltip is-viewer? (first selected-shapes) theme-token token
                                         half-applied? no-valid-value ref-not-in-active-set)]
             (dom/set-attribute! node "title" title))))]

    [:button {:class (stl/css-case
                      :token-pill true
                      :token-pill-no-icon (and (not status-icon?) (not errors?))
                      :token-pill-default can-edit?
                      :token-pill-disabled disabled?
                      :token-pill-applied (and can-edit? has-selected? (or half-applied? full-applied?))
                      :token-pill-invalid (and can-edit? errors?)
                      :token-pill-invalid-applied (and full-applied? errors? can-edit?)
                      :token-pill-viewer is-viewer?
                      :token-pill-applied-viewer (and is-viewer? has-selected?
                                                      (or half-applied? full-applied?))
                      :token-pill-invalid-viewer (and is-viewer?
                                                      errors?)
                      :token-pill-invalid-applied-viewer (and is-viewer?
                                                              (and full-applied? errors?)))
              :type "button"
              :on-focus on-hover

              :on-click on-click
              :on-mouse-enter on-hover

              :on-context-menu on-context-menu}
     (cond
       errors?
       [:> icon*
        {:icon-id i/broken-link
         :class (stl/css :token-pill-icon)}]

       color
       [:& color-bullet {:color color :mini true}]

       status-icon?
       [:> token-status-icon*
        {:icon-id token-status-id
         :class (stl/css :token-pill-icon)}])

     (if contains-path?
       (let [[first-part last-part] (cfh/split-by-last-period name)]
         [:span {:class (stl/css :divided-name-wrapper)
                 :aria-label name}
          [:span {:class (stl/css :first-name-wrapper)} first-part]
          [:span {:class (stl/css :last-name-wrapper)} last-part]])
       [:span {:class (stl/css :name-wrapper)
               :aria-label name}
        name])]))
