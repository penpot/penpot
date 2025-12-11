(ns app.main.ui.workspace.sidebar.options.menus.border-radius
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.token :as tk]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.hooks :as hooks]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn- all-equal?
  [shape]
  (= (:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape)))

(defn- check-border-radius-menu-props
  [old-props new-props]
  (let [old-values (unchecked-get old-props "values")
        new-values (unchecked-get new-props "values")
        old-applied-tokens (unchecked-get old-props "appliedTokens")
        new-applied-tokens (unchecked-get new-props "appliedTokens")]
    (and (identical? (unchecked-get old-props "class")
                     (unchecked-get new-props "class"))
         (identical? (unchecked-get old-props "ids")
                     (unchecked-get new-props "ids"))
         (identical? (unchecked-get old-props "shapes")
                     (unchecked-get new-props "shapes"))
         (identical? old-applied-tokens
                     new-applied-tokens)
         (identical? (get old-values :r1)
                     (get new-values :r1))
         (identical? (get old-values :r2)
                     (get new-values :r2))
         (identical? (get old-values :r3)
                     (get new-values :r3))
         (identical? (get old-values :r4)
                     (get new-values :r4)))))

(mf/defc numeric-input-wrapper*
  {::mf/private true}
  [{:keys [values name applied-tokens align on-detach radius] :rest props}]
  (let [tokens (mf/use-ctx muc/active-tokens-by-type)
        tokens (mf/with-memo [tokens name]
                 (delay
                   (-> (deref tokens)
                       (select-keys (get tk/tokens-by-input name))
                       (not-empty))))
        on-detach-attr
        (mf/use-fn
         (mf/deps on-detach name)
         #(on-detach % name))

        r1-value   (get applied-tokens :r1)
        all-token-equal? (and (seq applied-tokens) (all-equal? applied-tokens))
        all-values-equal? (all-equal? values)

        applied-token (cond
                        (not (seq applied-tokens))
                        nil

                        (and (= radius :all) (or (not all-values-equal?) (not all-token-equal?)))
                        :multiple

                        (and all-token-equal? all-values-equal? (= radius :all))
                        r1-value

                        :else
                        (get applied-tokens radius))


        placeholder (if (= radius :all)
                      (cond
                        (or (not all-values-equal?)
                            (not all-token-equal?))
                        (tr "settings.multiple")
                        :else
                        "--")

                      (cond
                        (or (= :multiple (:applied-tokens values))
                            (= :multiple (get values name)))
                        (tr "settings.multiple")
                        :else
                        "--"))


        props  (mf/spread-props props
                                {:placeholder placeholder
                                 :applied-token applied-token
                                 :tokens (if (delay? tokens) @tokens tokens)
                                 :align align
                                 :on-detach on-detach-attr
                                 :value values})]
    [:> numeric-input* props]))

(mf/defc border-radius-menu*
  {::mf/wrap [#(mf/memo' % check-border-radius-menu-props)]}
  [{:keys [class ids values applied-tokens]}]
  (let [token-numeric-inputs
        (features/use-feature "tokens/numeric-input")

        all-values-equal? (all-equal? values)

        radius-expanded* (mf/use-state false)
        radius-expanded  (deref radius-expanded*)

        ;; DETACH
        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token attr]
           (st/emit! (dwta/unapply-token {:token (first token)
                                          :attributes #{attr}
                                          :shape-ids ids}))))

        on-detach-all
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (run! #(on-detach-token token %) [:r1 :r2 :r3 :r4])))

        on-detach-r1
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token :r1)))

        on-detach-r2
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token :r2)))

        on-detach-r3
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token :r3)))

        on-detach-r4
        (mf/use-fn
         (mf/deps on-detach-token)
         (fn [token]
           (on-detach-token token :r4)))

        change-radius
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn]
           (dwsh/update-shapes ids
                               (fn [shape]
                                 (if (ctsr/has-radius? shape)
                                   (update-fn shape)
                                   shape))
                               {:reg-objects? true
                                :attrs [:r1 :r2 :r3 :r4]})))

        change-one-radius
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn attr]
           (dwsh/update-shapes ids
                               (fn [shape]
                                 (if (ctsr/has-radius? shape)
                                   (update-fn shape)
                                   shape))
                               {:reg-objects? true
                                :attrs [attr]})))

        toggle-radius-mode
        (mf/use-fn
         (mf/deps radius-expanded)
         (fn []
           (swap! radius-expanded* not)))


        on-all-radius-change
        (mf/use-fn
         (mf/deps change-radius ids)
         (fn [value]
           (if (or (string? value) (number? value))
             (st/emit!
              (change-radius (fn [shape]
                               (ctsr/set-radius-to-all-corners shape value))))
             (doseq [attr [:r1 :r2 :r3 :r4]]
               (st/emit!
                (dwta/toggle-token {:token     (first value)
                                    :attrs     #{attr}
                                    :shape-ids ids}))))))


        on-single-radius-change
        (mf/use-fn
         (mf/deps change-one-radius ids)
         (fn [value attr]
           (if (or (string? value) (number? value))
             (st/emit! (change-one-radius #(ctsr/set-radius-to-single-corner % attr value) attr))
             (st/emit! (dwta/toggle-border-radius-token {:token (first value)
                                                         :attrs #{attr}
                                                         :shape-ids ids})))))

        on-radius-r1-change #(on-single-radius-change % :r1)
        on-radius-r2-change #(on-single-radius-change % :r2)
        on-radius-r3-change #(on-single-radius-change % :r3)
        on-radius-r4-change #(on-single-radius-change % :r4)

        expand-stream
        (mf/with-memo []
          (->> st/stream
               (rx/filter (ptk/type? :expand-border-radius))))]

    (hooks/use-stream
     expand-stream
     #(reset! radius-expanded* true))

    (mf/with-effect [ids]
      (reset! radius-expanded* false))

    [:section {:class (dm/str class " " (stl/css :radius))
               :aria-label "border-radius-section"}
     (if (not radius-expanded)
       (if token-numeric-inputs
         [:> numeric-input-wrapper*
          {:on-change on-all-radius-change
           :on-detach on-detach-all
           :icon i/corner-radius
           :min 0
           :name :border-radius
           :nillable true
           :property (tr "workspace.options.radius")
           :class (stl/css :radius-wrapper)
           :applied-tokens applied-tokens
           :radius :all
           :align :right
           :values (if all-values-equal?
                     (if (nil? (:r1 values))
                       0
                       (:r1 values))
                     nil)}]

         [:div {:class (stl/css :radius-1)
                :title (tr "workspace.options.radius")}
          [:> icon* {:icon-id i/corner-radius
                     :size "s"
                     :class (stl/css :icon)}]
          [:> deprecated-input/numeric-input*
           {:placeholder (cond
                           (not all-values-equal?)
                           (tr "settings.multiple")
                           (= :multiple (:r1 values))
                           (tr "settings.multiple")
                           :else
                           "--")
            :min 0
            :nillable true
            :on-change on-all-radius-change
            :value (if all-values-equal?
                     (if (nil? (:r1 values))
                       0
                       (:r1 values))
                     nil)}]])

       (if token-numeric-inputs
         [:div {:class (stl/css :radius-4)}
          [:> numeric-input-wrapper*
           {:on-change on-radius-r1-change
            :on-detach on-detach-r1
            :min 0
            :name :border-radius
            :property (tr "workspace.options.radius-top-left")
            :applied-tokens applied-tokens
            :radius :r1
            :align :right
            :class (stl/css :radius-wrapper :dropdown-offset)
            :inner-class (stl/css :no-icon-input)
            :values  (:r1 values)}]

          [:> numeric-input-wrapper*
           {:on-change on-radius-r2-change
            :on-detach on-detach-r2
            :min 0
            :name :border-radius
            :nillable true
            :property (tr "workspace.options.radius-top-right")
            :applied-tokens applied-tokens
            :align :right
            :class (stl/css :radius-wrapper)
            :inner-class (stl/css :no-icon-input)
            :radius :r2
            :values (:r2 values)}]

          [:> numeric-input-wrapper*
           {:on-change on-radius-r4-change
            :on-detach on-detach-r4
            :min 0
            :name :border-radius
            :nillable true
            :property (tr "workspace.options.radius-bottom-left")
            :applied-tokens applied-tokens
            :class (stl/css :radius-wrapper :dropdown-offset)
            :inner-class (stl/css :no-icon-input)
            :radius :r4
            :align :right
            :values (:r4 values)}]

          [:> numeric-input-wrapper*
           {:on-change on-radius-r3-change
            :on-detach on-detach-r3
            :min 0
            :name :border-radius
            :nillable true
            :property (tr "workspace.options.radius-bottom-right")
            :applied-tokens applied-tokens
            :radius :r3
            :align :right
            :class (stl/css :radius-wrapper)
            :inner-class (stl/css :no-icon-input)
            :values (:r3 values)}]]

         [:div {:class (stl/css :radius-4)}
          [:div {:class (stl/css :small-input)}
           [:> deprecated-input/numeric-input*
            {:placeholder "--"
             :title (tr "workspace.options.radius-top-left")
             :min 0
             :on-change on-radius-r1-change
             :value (:r1 values)}]]

          [:div {:class (stl/css :small-input)}
           [:> deprecated-input/numeric-input*
            {:placeholder "--"
             :title (tr "workspace.options.radius-top-right")
             :min 0
             :on-change on-radius-r2-change
             :value (:r2 values)}]]

          [:div {:class (stl/css :small-input)}
           [:> deprecated-input/numeric-input*
            {:placeholder "--"
             :title (tr "workspace.options.radius-bottom-left")
             :min 0
             :on-change on-radius-r4-change
             :value (:r4 values)}]]

          [:div {:class (stl/css :small-input)}
           [:> deprecated-input/numeric-input*
            {:placeholder "--"
             :title (tr "workspace.options.radius-bottom-right")
             :min 0
             :on-change on-radius-r3-change
             :value (:r3 values)}]]]))

     [:> icon-button* {:variant "ghost"
                       :on-click toggle-radius-mode
                       :aria-pressed radius-expanded
                       :aria-label (if radius-expanded
                                     (tr "workspace.options.radius.hide-all-corners")
                                     (tr "workspace.options.radius.show-single-corners"))
                       :icon i/corner-radius}]]))
