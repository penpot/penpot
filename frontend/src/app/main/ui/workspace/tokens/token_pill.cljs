(ns app.main.ui.workspace.tokens.token-pill
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.components.color-bullet :refer [color-bullet]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.utilities.token.token-status :refer [token-status-icon*]]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
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
   :y "Y"})

;; Helper functions
(defn partially-applied-attr
  "Translates partially applied attributes based on the dictionary."
  [app-token-keys is-applied token-type-props]
  (let [{:keys [attributes all-attributes]} token-type-props
        filtered-keys (if all-attributes
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

(defn token-pill-tooltip
  "Generates a tooltip for a given token."
  [is-viewer shape token-type-props token half-applied no-valid-value ref-not-in-active-set]
  (let [{:keys [name value resolved-value type]} token
        {:keys [title]} token-type-props
        applied-tokens (:applied-tokens shape)
        app-token-vals (set (vals applied-tokens))
        app-token-keys (keys applied-tokens)
        is-applied? (contains? app-token-vals name)


        applied-to (if half-applied
                     (partially-applied-attr app-token-keys is-applied? token-type-props)
                     (tr "labels.all"))
        grouped-values (group-by dimensions-dictionary app-token-keys)

        base-title (dm/str "Token: " name "\n"
                           (tr "workspace.token.original-value" value) "\n"
                           (tr "workspace.token.resolved-value" resolved-value))]

    (cond
      ;; If there are errors, show the appropriate message
      ref-not-in-active-set
      (tr "workspace.token.ref-not-valid")

      no-valid-value
      (tr "workspace.token.value-not-valid")

      ;; If the token is applied and the user is a is-viewer, show the details
      (and is-applied? is-viewer)
      (->> [base-title
            (tr "workspace.token.applied-to")
            (if (= :dimensions type)
              (translate-and-format grouped-values)
              (str "- " title ": " applied-to))]
           (str/join "\n"))

      ;; Otherwise only show the base title
      :else base-title)))

(defn contains-reference-value?
  "Extracts the value between `{}` in a string and checks if it's in the provided vector."
  [text values]
  (let [match (second (re-find #"\{([^}]+)\}" text))]
    (boolean (some #(= % match) values))))

(mf/defc token-pill
  {::mf/wrap-props false}
  [{:keys [on-click token theme-token full-applied on-context-menu half-applied selected-shapes token-type-props active-theme-tokens]}]
  (let [{:keys [name value errors]} token
        is-reference (some #(= % "{") value)
        can-edit? (:can-edit (deref refs/permissions))

        is-viewer (not can-edit?)
        ref-not-in-active-set (and is-reference
                                   (not (contains-reference-value? value  (keys active-theme-tokens))))
        no-valid-value (seq errors)
        errors?   (or ref-not-in-active-set
                      no-valid-value)
        color     (when (seq (ctob/find-token-value-references value))
                    (wtt/resolved-token-bullet-color theme-token))
        contains-path? (str/includes? name ".")
        splitted-name (cfh/split-by-last-period name)
        color (or color (wtt/resolved-token-bullet-color token))

        on-click
        (mf/use-callback
         (mf/deps errors? on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (and (not (seq errors)) on-click)
             (on-click event))))

        token-status-id (cond
                          half-applied
                          "token-status-partial"
                          full-applied
                          "token-status-full"
                          :else
                          "token-status-non-applied")

        on-context-menu
        (mf/use-fn
         (mf/deps can-edit? on-context-menu)
         (fn [e]
           (dom/stop-propagation e)
           (when can-edit?
             (on-context-menu e))))

        on-click
        (mf/use-fn
         (mf/deps errors? on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (and can-edit? (not (seq errors)) on-click)
             (on-click event))))

        on-hover
        (mf/use-fn
         (mf/deps selected-shapes is-viewer)
         (fn [event]
           (let [node (dom/get-current-target event)
                 title (token-pill-tooltip  is-viewer (first selected-shapes) token-type-props token half-applied no-valid-value ref-not-in-active-set)]
             (dom/set-attribute! node "title" title))))]

    [:button {:class (stl/css-case :token-pill true
                                   :token-pill-default can-edit?
                                   :token-pill-applied (and can-edit? (or half-applied full-applied))
                                   :token-pill-invalid (and can-edit? errors?)
                                   :token-pill-invalid-applied (and full-applied errors? can-edit?)
                                   :token-pill-viewer is-viewer
                                   :token-pill-applied-viewer (and is-viewer
                                                                   (or half-applied full-applied))
                                   :token-pill-invalid-viewer (and is-viewer
                                                                   errors?)
                                   :token-pill-invalid-applied-viewer (and is-viewer
                                                                           (and full-applied errors?)))
              :type "button"
              :on-click on-click
              :on-mouse-enter on-hover
              :on-context-menu on-context-menu}
     (cond
       errors?
       [:> icon*
        {:icon-id "broken-link"
         :class (stl/css :token-pill-icon)}]
       color
       [:& color-bullet {:color color
                         :mini true}]
       :else
       [:> token-status-icon*
        {:icon-id token-status-id
         :class (stl/css :token-pill-icon)}])
     (if contains-path?
       [:span {:class (stl/css :divided-name-wrapper)
               :aria-label name}
        [:span {:class (stl/css :first-name-wrapper)}
         (first splitted-name)]
        [:span {:class (stl/css :last-name-wrapper)}
         (last splitted-name)]]
       [:span {:class (stl/css :name-wrapper)
               :aria-label name}
        name])]))