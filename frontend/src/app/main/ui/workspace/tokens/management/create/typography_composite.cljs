(ns app.main.ui.workspace.tokens.management.create.typography-composite
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as cto]
   [app.main.data.style-dictionary :as sd]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token*]]
   [app.main.ui.workspace.tokens.management.create.shared.font-combobox :refer [font-picker-combobox*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))


(def ^:private typography-inputs
  #(d/ordered-map
    :font-family
    {:label (tr "workspace.tokens.token-font-family-value")
     :icon i/text-font-family
     :placeholder (tr "workspace.tokens.token-font-family-value-enter")}
    :font-size
    {:label "Font Size"
     :icon i/text-font-size
     :placeholder (tr "workspace.tokens.font-size-value-enter")}
    :font-weight
    {:label "Font Weight"
     :icon i/text-font-weight
     :placeholder (tr "workspace.tokens.font-weight-value-enter")}
    :line-height
    {:label "Line Height"
     :icon i/text-lineheight
     :placeholder (tr "workspace.tokens.line-height-value-enter")}
    :letter-spacing
    {:label "Letter Spacing"
     :icon i/text-letterspacing
     :placeholder (tr "workspace.tokens.letter-spacing-value-enter-composite")}
    :text-case
    {:label "Text Case"
     :icon i/text-mixed
     :placeholder (tr "workspace.tokens.text-case-value-enter")}
    :text-decoration
    {:label "Text Decoration"
     :icon i/text-underlined
     :placeholder (tr "workspace.tokens.text-decoration-value-enter")}))

(mf/defc typography-value-inputs*
  [{:keys [default-value on-blur on-update-value token-resolve-result]}]
  (let [composite-token? (not (cto/typography-composite-token-reference? (:value token-resolve-result)))
        typography-inputs (mf/use-memo typography-inputs)
        errors-by-key (sd/collect-typography-errors token-resolve-result)]
    [:div {:class (stl/css :nested-input-row)}
     (for [[token-type {:keys [label placeholder icon]}] typography-inputs]
       (let [value (get default-value token-type)
             resolved (get-in token-resolve-result [:resolved-value token-type])
             errors   (get errors-by-key token-type)

             should-show? (or (and (some? resolved)
                                   (not= value (str resolved)))
                              (seq errors))

             token-prop  (when (and composite-token? should-show?)
                           (d/without-nils
                            {:resolved-value (when-not (str/empty? resolved) resolved)
                             :errors errors}))

             input-ref (mf/use-ref)

             on-external-update-value
             (mf/use-fn
              (mf/deps on-update-value)
              (fn [next-value]
                (let [element (mf/ref-val input-ref)]
                  (dom/set-value! element next-value)
                  (on-update-value #js {:target element
                                        :tokenType :font-family}))))

             on-change
             (mf/use-fn
              (mf/deps token-type)
              ;; Passing token-type via event to prevent deep function adapting & passing of type
              (fn [event]
                (-> (obj/set! event "tokenType" token-type)
                    (on-update-value))))]

         [:div {:key (str token-type)
                :class (stl/css :input-row)}
          (case token-type
            :font-family
            [:> font-picker-combobox*
             {:aria-label label
              :placeholder placeholder
              :input-ref input-ref
              :default-value (when value (cto/join-font-family value))
              :on-blur on-blur
              :on-update-value on-change
              :on-external-update-value on-external-update-value
              :token-resolve-result token-prop}]
            [:> input-token*
             {:aria-label label
              :placeholder placeholder
              :default-value value
              :on-blur on-blur
              :icon icon
              :on-change on-change
              :token-resolve-result token-prop}])]))]))