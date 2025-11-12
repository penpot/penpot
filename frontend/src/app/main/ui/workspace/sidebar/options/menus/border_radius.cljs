(ns app.main.ui.workspace.sidebar.options.menus.border-radius
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
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
        new-values (unchecked-get new-props "values")]
    (and (identical? (unchecked-get old-props "class")
                     (unchecked-get new-props "class"))
         (identical? (unchecked-get old-props "ids")
                     (unchecked-get new-props "ids"))
         (identical? (get old-values :r1)
                     (get new-values :r1))
         (identical? (get old-values :r2)
                     (get new-values :r2))
         (identical? (get old-values :r3)
                     (get new-values :r3))
         (identical? (get old-values :r4)
                     (get new-values :r4)))))

(mf/defc border-radius-menu*
  {::mf/wrap [#(mf/memo' % check-border-radius-menu-props)]}
  [{:keys [class ids values]}]
  (let [all-equal?       (all-equal? values)
        radius-expanded* (mf/use-state false)
        radius-expanded  (deref radius-expanded*)

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

        toggle-radius-mode
        (mf/use-fn
         (mf/deps radius-expanded)
         (fn []
           (swap! radius-expanded* not)))

        on-single-radius-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [value]
           (st/emit!
            (change-radius (fn [shape]
                             (ctsr/set-radius-to-all-corners shape value))))))


        on-radius-4-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [value attr]
           (st/emit! (change-radius #(ctsr/set-radius-to-single-corner % attr value)))))

        on-radius-r1-change #(on-radius-4-change % :r1)
        on-radius-r2-change #(on-radius-4-change % :r2)
        on-radius-r3-change #(on-radius-4-change % :r3)
        on-radius-r4-change #(on-radius-4-change % :r4)

        expand-stream
        (mf/with-memo []
          (->> st/stream
               (rx/filter (ptk/type? :expand-border-radius))))]

    (hooks/use-stream
     expand-stream
     #(reset! radius-expanded* true))

    (mf/with-effect [ids]
      (reset! radius-expanded* false))

    [:div {:class (dm/str class " " (stl/css :radius))}
     (if (not radius-expanded)
       [:div {:class (stl/css :radius-1)
              :title (tr "workspace.options.radius")}
        [:> icon* {:icon-id i/corner-radius
                   :size "s"
                   :class (stl/css :icon)}]
        [:> deprecated-input/numeric-input*
         {:placeholder (cond
                         (not all-equal?)
                         "Mixed"
                         (= :multiple (:r1 values))
                         (tr "settings.multiple")
                         :else
                         "--")
          :min 0
          :nillable true
          :on-change on-single-radius-change
          :value (if all-equal? (:r1 values) nil)}]]

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
           :value (:r3 values)}]]])

     [:> icon-button* {:class (stl/css-case :selected radius-expanded)
                       :variant "ghost"
                       :on-click toggle-radius-mode
                       :aria-label (if radius-expanded
                                     (tr "workspace.options.radius.hide-all-corners")
                                     (tr "workspace.options.radius.show-single-corners"))
                       :icon i/corner-radius}]]))
