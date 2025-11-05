(ns app.main.ui.workspace.tokens.management.create.shared.composite-tabs
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc composite-reference-input*
  {::mf/private true}
  [{:keys [default-value on-blur on-update-value token-resolve-result reference-label reference-icon is-reference-fn]}]
  [:> input-token*
   {:aria-label (tr "labels.reference")
    :placeholder reference-label
    :icon reference-icon
    :default-value (when (is-reference-fn default-value) default-value)
    :on-blur on-blur
    :on-change on-update-value
    :token-resolve-result (when (or
                                 (:errors token-resolve-result)
                                 (string? (:value token-resolve-result)))
                            token-resolve-result)}])

(mf/defc composite-tabs*
  [{:keys [default-value
           on-update-value
           on-external-update-value
           on-value-resolve
           clear-resolve-value
           custom-input-token-value-props]
    :rest props}]
  (let [;; Active Tab State
        {:keys [active-tab
                composite-tab
                is-reference-fn
                reference-icon
                reference-label
                set-active-tab
                title
                update-composite-backup-value]} custom-input-token-value-props
        reference-tab-active? (= :reference active-tab)
        ;; Backup value ref
        ;; Used to restore the previously entered value when switching tabs
        ;; Uses ref to not trigger state updates during update
        backup-state-ref (mf/use-var
                          (if reference-tab-active?
                            {:reference default-value}
                            {:composite default-value}))
        default-value (get @backup-state-ref active-tab)

        on-toggle-tab
        (mf/use-fn
         (mf/deps active-tab on-external-update-value on-value-resolve clear-resolve-value)
         (fn []
           (let [next-tab (if (= active-tab :composite) :reference :composite)]
             ;; Clear the resolved value so it wont show up before the next-tab value has resolved
             (clear-resolve-value)
             ;; Restore the internal value from backup
             (on-external-update-value (get @backup-state-ref next-tab))
             (set-active-tab next-tab))))

        update-composite-value
        (mf/use-fn
         (fn [f]
           (clear-resolve-value)
           (swap! backup-state-ref f)
           (on-external-update-value (get @backup-state-ref :composite))))

        ;; Store updated value in backup-state-ref
        on-update-value'
        (mf/use-fn
         (mf/deps on-update-value reference-tab-active? update-composite-backup-value)
         (fn [e]
           (if reference-tab-active?
             (swap! backup-state-ref assoc :reference (dom/get-target-val e))
             (swap! backup-state-ref update :composite #(update-composite-backup-value % e)))
           (on-update-value e)))]
    [:div {:class (stl/css :typography-inputs-row)}
     [:div {:class (stl/css :title-bar)}
      [:div {:class (stl/css :title)} title]
      [:& radio-buttons {:selected (if reference-tab-active? "reference" "composite")
                         :on-change on-toggle-tab
                         :name "reference-composite-tab"}
       [:& radio-button {:icon deprecated-icon/layers
                         :value "composite"
                         :title (tr "workspace.tokens.individual-tokens")
                         :id "composite-opt"}]
       [:& radio-button {:icon deprecated-icon/tokens
                         :value "reference"
                         :title (tr "workspace.tokens.use-reference")
                         :id "reference-opt"}]]]
     [:div {:class (stl/css :typography-inputs)}
      (if reference-tab-active?
        [:> composite-reference-input*
         (mf/spread-props props {:default-value default-value
                                 :on-update-value on-update-value'
                                 :reference-icon reference-icon
                                 :reference-label reference-label
                                 :is-reference-fn is-reference-fn})]
        [:> composite-tab
         (mf/spread-props props {:default-value default-value
                                 :on-update-value on-update-value'
                                 :update-composite-value update-composite-value})])]]))