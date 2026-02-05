(ns app.main.ui.workspace.tokens.management.node-context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private schema:token-node-context-menu
  [:map
   [:on-rename-node fn?]
   [:on-delete-node fn?]])

(def ^:private tokens-node-menu-ref
  (l/derived :token-node-context-menu refs/workspace-tokens))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc token-node-context-menu*
  {::mf/schema schema:token-node-context-menu}
  [{:keys [on-rename-node on-delete-node]}]
  (let [mdata               (mf/deref tokens-node-menu-ref)
        is-open?            (boolean mdata)
        dropdown-ref        (mf/use-ref)
        dropdown-action    (mf/use-ref)
        dropdown-direction* (mf/use-state "down")
        dropdown-direction  (deref dropdown-direction*)
        dropdown-direction-change* (mf/use-ref 0)
        top                 (+ (get-in mdata [:position :y]) 5)
        left                (+ (get-in mdata [:position :x]) 5)
        rename-node         (mf/use-fn
                             (mf/deps mdata)
                             (fn []
                               (let [node (get mdata :node)
                                     type (get mdata :type)]
                                 (when node
                                   (on-rename-node node type)))))
        delete-node          (mf/use-fn
                              (mf/deps mdata)
                              (fn []
                                (let [node (get mdata :node)
                                      type (get mdata :type)]
                                  (when node
                                    (on-delete-node node type)))))]

    (mf/with-effect [is-open?]
      (when (and (not= 0 (mf/ref-val dropdown-direction-change*)) (= false is-open?))
        (reset! dropdown-direction* "down")
        (mf/set-ref-val! dropdown-direction-change* 0)))

    (mf/with-effect [is-open? dropdown-ref dropdown-action]
      (let [dropdown-element (mf/ref-val dropdown-ref)]
        (when (and (= 0 (mf/ref-val dropdown-direction-change*)) dropdown-element)
          (let [is-outside? (dom/is-element-outside? dropdown-element)]
            (reset! dropdown-direction* (if is-outside? "up" "down"))
            (mf/set-ref-val! dropdown-direction-change* (inc (mf/ref-val dropdown-direction-change*)))))))

    ;; FIXME: perf optimization

    (when is-open?
      (mf/portal
       (mf/html
        [:& dropdown {:show is-open?
                      :on-close #(st/emit! (dwtl/assign-token-node-context-menu nil))}
         [:div {:class (stl/css :token-node-context-menu)
                :data-testid "tokens-context-menu-for-token-node"
                :ref dropdown-ref
                :data-direction dropdown-direction
                :style {:--bottom (if (= dropdown-direction "up")
                                    "40px"
                                    "unset")
                        :--top (dm/str top "px")
                        :left (dm/str left "px")}
                :on-context-menu prevent-default}
          (when mdata
            [:ul {:class (stl/css :token-node-context-menu-list)}
             [:li {:class (stl/css :token-node-context-menu-listitem)}
              [:button {:class (stl/css :token-node-context-menu-action)
                        :type "button"
                        :on-click rename-node}
               (tr "labels.rename")]]
             [:li {:class (stl/css :token-node-context-menu-listitem)}
              [:button {:class (stl/css :token-node-context-menu-action)
                        :type "button"
                        :on-click delete-node}
               (tr "labels.delete")]]])]])
       (dom/get-body)))))
