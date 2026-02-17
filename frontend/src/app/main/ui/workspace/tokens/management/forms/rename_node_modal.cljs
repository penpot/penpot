(ns app.main.ui.workspace.tokens.management.forms.rename-node-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.forms :as fc]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(defn- make-schema
  [tokens-tree]
  (sm/schema
   [:map
    [:name
     [:and
      [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
      (sm/update-properties cto/node-name-ref assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
      [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
       #(not (cft/token-name-path-exists? % tokens-tree))]]]]))

(mf/defc rename-node-form*
  [{:keys [node tokens-tree on-close on-submit]}]
  (let [schema
        (mf/with-memo [tokens-tree]
          (make-schema tokens-tree))

        initial (mf/with-memo [node]
                  {:name (:name node)})

        form (fm/use-form :schema schema
                          :initial initial)

        on-submit (mf/use-fn
                   (mf/deps form on-submit)
                   (fn []
                     (let [name (get-in @form [:clean-data :name])]
                       (when (and (get-in @form [:touched :name]) (not= name (:name node)))
                         (on-submit {:new-name name})))))

        is-disabled? (or (not (:valid @form))
                         (not (get-in @form [:touched :name]))
                         (= (get-in @form [:clean-data :name]) (:name node)))]


    [:div
     [:> heading* {:level 2
                   :typography "headline-medium"
                   :class (stl/css :form-modal-title)}
      (tr "workspace.tokens.rename-group")]
     [:> fc/form* {:class (stl/css :form-wrapper)
                   :form form
                   :on-submit on-submit}
      [:> fc/form-input* {:id "kmscdkmcsdkmcvd"
                          :name :name
                          :label (tr "workspace.tokens.token-name")
                          :placeholder (tr "workspace.tokens.token-name")
                          :max-length 255
                          :variant "comfortable"
                          :hint-type "hint"
                          :hint-message (tr "workspace.tokens.rename-group-name-hint")
                          :auto-focus true}]
      [:div {:class (stl/css :form-actions)}
       [:> button* {:variant "secondary"
                    :name "cancel"
                    :on-click on-close} (tr "labels.cancel")]
       [:> fc/form-submit* {:variant "primary"
                            :disabled is-disabled?
                            :name "rename"} (tr "labels.rename")]]]]))

(mf/defc rename-node-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/rename-node}
  [{:keys [node tokens-in-active-set on-rename]}]

  (let [tokens-tree-in-selected-set
        (mf/with-memo [tokens-in-active-set node]
          (-> (ctob/tokens-tree tokens-in-active-set)
              (d/dissoc-in (:name node))))

        close-modal
        (mf/use-fn
         (mf/deps [])
         (fn []
           (st/emit! (modal/hide))))

        rename
        (mf/use-fn
         (mf/deps on-rename)
         (fn [new-name]
           (on-rename {:new-name new-name})))

        on-key-down
        (mf/use-fn
         (mf/deps [close-modal])
         (fn [event]
           (when (kbd/esc? event)
             (close-modal))))]

    [:div {:class (stl/css :modal-overlay) :on-key-down on-key-down}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click close-modal
                        :aria-label (tr "labels.close")
                        :variant "ghost"
                        :icon i/close}]
      [:> rename-node-form* {:node node
                             :tokens-tree tokens-tree-in-selected-set
                             :on-close close-modal
                             :on-submit rename}]]]))


;;    (fn []
;;  ;; Call shared remapping logic
;;  (let [old-token-name (:old-token-name remap-modal)
;;        new-token-name (:new-token-name remap-modal)]
;;    (st/emit! [:tokens/remap-tokens old-token-name new-token-name]))
;;  (when (fn? on-remap)
;;    (on-remap))))



;; remap (mf/use-fn
;;        (mf/deps form on-submit)
;;        (fn []
;;          (let [name (get-in @form [:clean-data :name])
;;                path (str (d/name type) "." name)]
;;            (prn "Submitting rename node form with name: " name " and path: " path))
;;          #_(let [{:keys [clean-data valid extra-errors async-errors]} @form]
;;              (when (and valid
;;                         (empty? extra-errors)
;;                         (empty? async-errors))
;;                (on-submit clean-data)))))

;; submit (mf/use-fn
;;         (mf/deps form on-submit)
;;         (fn [_ event]
;;           (let [event   (dom/event->native-event event)
;;                 submitter (dom/get-event-submitter event)
;;                 handler (.-name submitter)
;;                 handlerKey (keyword handler)]
;;             (if (= handlerKey :rename)
;;               (rename)
;;               (remap)))))]
