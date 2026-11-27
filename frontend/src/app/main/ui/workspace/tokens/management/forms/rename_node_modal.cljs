(ns app.main.ui.workspace.tokens.management.forms.rename-node-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.helpers :as dh]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.tokens.management.forms.rename-scope :refer [rename-scope*]]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc rename-node-form*
  [{:keys [new-node-name node type set-id active-tokens tokens-tree variant on-close on-submit]}]
  (let [tokenscript? (contains? cf/flags :tokenscript)
        duplicate?   (= variant "duplicate")

        ;; The sets a group rename reaches, picked by the user; a copy is
        ;; checked against its set alone.
        scope*       (mf/use-state :all)
        scope        (deref scope*)

        make-schema #(if tokenscript?
                       (cfo/make-group-name-schema (dh/lookup-file-data @st/state) set-id type node duplicate?
                                                   (if duplicate? :set scope))
                       (cfo/make-node-token-schema active-tokens tokens-tree node))

        schema
        (mf/with-memo [active-tokens scope]
          (make-schema))

        initial (mf/with-memo [node new-node-name]
                  {:name (d/nilv new-node-name (:name node))})

        form (fm/use-form :schema schema
                          :initial initial)

        on-submit (mf/use-fn
                   (mf/deps form on-submit scope)
                   (fn []
                     (let [name (get-in @form [:clean-data :name])]
                       (when (not= name (:name node))
                         (if (and tokenscript? (not duplicate?))
                           (on-submit name scope)
                           (on-submit name))))))

        tokens-lib (mf/deref refs/tokens-lib)

        renaming? (and tokenscript?
                       (not duplicate?)
                       (cfo/rename-scope-choice? tokens-lib)
                       (not= (str/trim (get-in @form [:data :name] "")) (:name node)))

        is-disabled? (or (not (:valid @form))
                         (= (get-in @form [:clean-data :name]) (:name node)))

        hint-path (mf/with-memo [@form node]
                    (let [new-name (get-in @form [:clean-data :name])
                          path (str (:path node))
                          new-path (if tokenscript?
                                     (ctob/rename-path node new-name)
                                     (str/replace path (:name node) new-name))]
                      (if (get-in @form [:touched :name])
                        new-path
                        path)))]

    [:> fc/form* {:class (stl/css :form-wrapper)
                  :form form
                  :on-submit on-submit}
     [:> heading* {:level 2
                   :typography "headline-medium"
                   :class (stl/css :form-modal-title)}
      (if (= variant "rename")
        (tr "workspace.tokens.rename-group")
        (tr "workspace.tokens.duplicate-group"))]
     [:> fc/form-input* {:id "rename-node"
                         :name :name
                         :label (tr "workspace.tokens.token-name")
                         :placeholder (tr "workspace.tokens.token-name")
                         :max-length 255
                         :variant "comfortable"
                         :hint-type "hint"
                         :hint-message (when (= variant "rename") (tr "workspace.tokens.rename-group-name-hint" hint-path))
                         :auto-focus true}]
     (when renaming?
       [:> rename-scope* {:scope scope
                          :on-change #(reset! scope* %)}])
     [:div {:class (stl/css :form-actions)}
      [:> button* {:variant "secondary"
                   :name "cancel"
                   :on-click on-close} (tr "labels.cancel")]
      [:> fc/form-submit* {:variant "primary"
                           :disabled is-disabled?
                           :name "rename"} (if (= variant "rename") (tr "labels.rename") (tr "labels.duplicate"))]]]))

(mf/defc rename-node-modal
  {::mf/register modal/components
   ::mf/register-as :tokens/rename-node}
  [{:keys [new-node-name node type set-id tokens-in-active-set on-rename variant]}]

  (let [variant (d/nilv variant "rename") ;; "rename" or "duplicate"

        tokens-tree-in-selected-set
        (mf/with-memo [tokens-in-active-set node]
          (-> (ctob/tokens-tree tokens-in-active-set)
              (d/dissoc-in (:name node))))

        close-modal
        (mf/use-fn
         (fn []
           (st/emit! (modal/hide))))

        rename
        (mf/use-fn
         (mf/deps on-rename)
         (fn [new-name & scope]
           (apply on-rename new-name scope)))

        on-key-down
        (mf/use-fn
         (mf/deps [close-modal])
         (fn [event]
           (when (kbd/esc? event)
             (close-modal))))]

    [:div {:class (stl/css :modal-overlay)
           :on-key-down on-key-down
           :data-testid "token-rename-node-modal"}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :on-click close-modal
                        :aria-label (tr "labels.close")
                        :variant "ghost"
                        :icon i/close}]
      [:> rename-node-form* {:new-node-name new-node-name
                             :node node
                             :type type
                             :set-id set-id
                             :variant variant
                             :active-tokens tokens-in-active-set
                             :tokens-tree tokens-tree-in-selected-set
                             :on-close close-modal
                             :on-submit rename}]]]))
