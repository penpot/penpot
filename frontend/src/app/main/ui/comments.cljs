;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.comments
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def mentions-context (mf/create-context nil))

(def r-mentions-split #"@\[[^\]]*\]\([^\)]*\)")
(def r-mentions #"@\[([^\]]*)\]\(([^\)]*)\)")

(defn parse-comment
  [comment]
  (d/interleave-all
   (->> (str/split comment r-mentions-split)
        (map #(hash-map :type :text :content %)))

   (->> (re-seq r-mentions comment)
        (map (fn [[_ user id]]
               {:type :mention
                :content user
                :data {:id id}})))))

(def comments-local-options (l/derived :options refs/comments-local))

(defn create-text-node
  ([]
   (create-text-node ""))
  ([text]
   (-> (dom/create-element "span")
       (dom/set-data! "type" "text")
       (dom/set-html! (if (empty? text) "&#8203;" text)))))

(defn create-mention-node
  [user]
  (-> (dom/create-element "span")
      (dom/set-data! "type" "mention")
      (dom/set-data! "user-id" (-> user :id str))
      (obj/set! "textContent" (-> user :fullname))))

(defn current-text-node
  [node]
  (let [selection     (wapi/get-selection)
        anchor-node   (wapi/get-anchor-node selection)
        anchor-offset (wapi/get-anchor-offset selection)]
    (when (and node (.contains node anchor-node))
      (let [span-node
            (if (instance? js/Text anchor-node)
              (dom/get-parent anchor-node)
              anchor-node)

            container   (dom/get-parent span-node)]
        (when (= node container)
          [span-node anchor-offset])))))

(defn parse-nodes
  [node]
  (->> (dom/get-children node)
       (map
        (fn [node]
          (cond
            (and (instance? js/HTMLElement node) (dom/get-data node "user-id"))
            (str/ffmt "@[%](%)" (.-textContent node) (dom/get-data node "user-id"))

            :else
            (.-textContent node))))
       (str/join "")))

(mf/defc comment-input
  {::mf/wrap-props false}
  [props]

  (let [value            (d/nilv (unchecked-get props "value") "")
        prev-value       (h/use-previous value)

        local-ref    (mf/use-ref nil)
        mentions-str (mf/use-ctx mentions-context)

        cur-mention  (mf/use-var nil)

        on-focus         (unchecked-get props "on-focus")
        on-blur          (unchecked-get props "on-blur")
        placeholder      (unchecked-get props "placeholder")
        on-change        (unchecked-get props "on-change")
        on-esc           (unchecked-get props "on-esc")
        on-ctrl-enter    (unchecked-get props "on-ctrl-enter")
        max-length       (unchecked-get props "max-length")
        autofocus?       (unchecked-get props "autofocus")

        init-input
        (mf/use-callback
         (fn [node]
           (mf/set-ref-val! local-ref node)
           (when node
             (let [empty-node (create-text-node)]
               (dom/append-child! node empty-node)))))

        handle-input
        (mf/use-callback
         (mf/deps on-change)
         (fn []
           (let [node     (mf/ref-val local-ref)
                 children (dom/get-children node)]
             (doseq [child-node children]
               (when (not= (.-tagName child-node) "SPAN")
                 (.remove child-node)))

             (when (= 0 (.-length children))
               (dom/append-child! node (create-text-node)))

             (let [new-input (parse-nodes node)]
               (when (and on-change (<= (count new-input) max-length))
                 (on-change new-input))))))

        handle-select
        (mf/use-callback
         (fn []
           (let [node (mf/ref-val local-ref)
                 [span-node offset] (current-text-node node)]
             (when span-node
               (let [node-text (subs (dom/get-text span-node) 0 offset)

                     current-at-symbol
                     (str/last-index-of (subs node-text 0 offset) "@")

                     mention-text
                     (subs node-text current-at-symbol)]

                 (if (re-matches #"@\w*" mention-text)
                   (do
                     (reset! cur-mention mention-text)
                     (rx/push! mentions-str {:type :display-mentions})
                     (let [mention (subs mention-text 1)]
                       (when (d/not-empty? mention)
                         (rx/push! mentions-str {:type :filter-mentions :data mention}))))
                   (do
                     (reset! cur-mention nil)
                     (rx/push! mentions-str {:type :hide-mentions}))))))))

        handle-focus
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/set-css-property! (mf/ref-val local-ref) "--placeholder" "")
           (when on-focus
             (on-focus event))))

        handle-blur
        (mf/use-callback
         (mf/deps value)
         (fn [event]
           (when (empty? value)
             (let [node (mf/ref-val local-ref)]
               (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))))

           (when on-blur
             (on-blur event))))

        handle-insert-mention
        (fn [data]
          (let [node (mf/ref-val local-ref)
                [span-node offset] (current-text-node node)]
            (when span-node
              (let [node-text
                    (dom/get-text span-node)

                    current-at-symbol
                    (str/last-index-of (subs node-text 0 offset) "@")

                    mention
                    (re-find #"@\w*" (subs node-text current-at-symbol))

                    prefix
                    (subs node-text 0 current-at-symbol)

                    suffix
                    (subs node-text (+ current-at-symbol (count mention)))

                    mention-span (create-mention-node (:user data))
                    after-span (create-text-node suffix)
                    sel (wapi/get-selection)]

                (dom/set-html! span-node prefix)
                (dom/insert-after! node span-node mention-span)
                (dom/insert-after! node mention-span after-span)
                (wapi/select-all-children! sel after-span)
                (wapi/collapse-end! sel)

                (when on-change
                  (on-change (parse-nodes node)))))))

        handle-key-down
        (mf/use-fn
         (mf/deps on-esc on-ctrl-enter handle-select)
         (fn [event]
           (handle-select event)
           (cond
             (and @cur-mention (kbd/enter? event))
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (rx/push! mentions-str {:type :insert-selected-mention}))

             (and @cur-mention (kbd/down-arrow? event))
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (rx/push! mentions-str {:type :insert-next-mention}))

             (and @cur-mention (kbd/up-arrow? event))
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (rx/push! mentions-str {:type :insert-prev-mention}))

             (and @cur-mention (kbd/esc? event))
             (do (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (rx/push! mentions-str {:type :hide-mentions}))

             (and (kbd/esc? event) (fn? on-esc))
             (on-esc event)

             (and (kbd/mod? event) (kbd/enter? event) (fn? on-ctrl-enter))
             (on-ctrl-enter event))))]

    (mf/use-layout-effect
     (mf/deps autofocus?)
     (fn []
       (when autofocus?
         (dom/focus! (mf/ref-val local-ref)))))

    ;; Creates the handlers for selection
    (mf/use-effect
     (mf/deps handle-select)
     (fn []
       (let [handle-select* handle-select]
         (js/document.addEventListener "selectionchange" handle-select*)
         #(js/document.removeEventListener "selectionchange" handle-select*))))

    ;; Effect to communicate with the mentions panel
    (mf/use-effect
     (fn []
       (when mentions-str
         (->> mentions-str
              (rx/subs!
               (fn [{:keys [type data]}]
                 (case type
                   :insert-mention
                   (handle-insert-mention data)

                   nil)))))))

    ;; Auto resize input to display the comment
    (mf/use-layout-effect
     nil
     (fn []
       (let [node (mf/ref-val local-ref)]
         (set! (.-height (.-style node)) "0")
         (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px")))))

    (mf/use-effect
     (mf/deps value prev-value)
     (fn []
       (let [node (mf/ref-val local-ref)]
         (when (and (d/not-empty? prev-value) (empty? value))
           (dom/set-html! node "")
           (dom/append-child! node (create-text-node))
           (dom/focus! node))

         (when (some? node)
           (if (empty? value)
             (dom/set-css-property! node "--placeholder" (dm/str "\"" placeholder "\""))
             (dom/set-css-property! node "--placeholder" ""))))))

    [:div
     {:class (stl/css :comment-input)
      :content-editable "plaintext-only"
      :suppress-content-editable-warning true
      :on-input handle-input
      :ref init-input
      :on-key-down handle-key-down
      :on-focus handle-focus
      :on-blur handle-blur}]))

(mf/defc mentions-panel
  [{:keys [users]}]

  (let [mentions-str (mf/use-ctx mentions-context)

        mention-state
        (mf/use-state {:display? false
                       :mention-filter ""
                       :selected 0})

        {:keys [display? mention-filter selected]} @mention-state

        mentions-users
        (mf/use-memo
         (mf/deps mention-filter)
         #(->> (vals users)
               (filter
                (fn [{:keys [fullname email]}]
                  (or (not mention-filter)
                      (empty? mention-filter)
                      (str/includes? (str/lower fullname) (str/lower mention-filter))
                      (str/includes? (str/lower email) (str/lower mention-filter)))))
               (take 4)
               (into [])))

        selected (mth/clamp selected 0 (dec (count mentions-users)))

        handle-click-mention
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (let [id (-> (dom/get-current-target event)
                        (dom/get-data "user-id")
                        (uuid/uuid))]
             (rx/push! mentions-str {:type :insert-mention
                                     :data {:user (get users id)}}))))]

    (mf/use-effect
     (mf/deps mentions-users selected)
     (fn []
       (let [sub
             (->> mentions-str
                  (rx/subs!
                   (fn [{:keys [type data]}]
                     (case type
                       ;; Display the mentions dialog
                       :display-mentions
                       (swap! mention-state assoc :display? true)

                       ;; Hide mentions
                       :hide-mentions
                       (swap! mention-state assoc :display? false :mention-filter "")

                       ;; Filter the metions by some characters
                       :filter-mentions
                       (swap! mention-state assoc :mention-filter data)

                       :insert-selected-mention
                       (rx/push! mentions-str {:type :insert-mention
                                               :data {:user (get mentions-users selected)}})

                       :insert-next-mention
                       (swap! mention-state update :selected #(mth/clamp (inc %) 0 (dec (count mentions-users))))

                       :insert-prev-mention
                       (swap! mention-state update :selected #(mth/clamp (dec %) 0 (dec (count mentions-users))))

                       ;;
                       nil))))]
         #(rx/dispose! sub))))

    (when display?
      [:div {:class (stl/css :comments-mentions-choice)}
       (for [[idx {:keys [id fullname email] :as user}] (d/enumerate mentions-users)]
         [:div {:key id
                :on-pointer-down handle-click-mention
                :data-user-id (dm/str id)
                :class (stl/css-case :comments-mentions-entry true
                                     :is-selected (= selected idx))}
          [:img {:class (stl/css :comments-mentions-avatar)
                 :src (cfg/resolve-profile-photo-url user)}]
          [:div {:class (stl/css :comments-mentions-name)} fullname]
          [:div {:class (stl/css :comments-mentions-email)} email]])])))

(mf/defc resizing-textarea
  {::mf/wrap-props false}
  [props]
  (let [value            (d/nilv (unchecked-get props "value") "")
        on-focus         (unchecked-get props "on-focus")
        on-blur          (unchecked-get props "on-blur")
        placeholder      (unchecked-get props "placeholder")
        max-length       (unchecked-get props "max-length")
        on-change        (unchecked-get props "on-change")
        on-esc           (unchecked-get props "on-esc")
        on-ctrl-enter    (unchecked-get props "on-ctrl-enter")
        autofocus?       (unchecked-get props "autofocus")
        select-on-focus? (unchecked-get props "select-on-focus")

        local-ref   (mf/use-ref)

        on-change*
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [content (dom/get-target-val event)]
             (on-change content))))

        on-key-down
        (mf/use-fn
         (mf/deps on-esc on-ctrl-enter on-change*)
         (fn [event]
           (cond
             (and (kbd/esc? event) (fn? on-esc)) (on-esc event)
             (and (kbd/mod? event) (kbd/enter? event) (fn? on-ctrl-enter))
             (do
               (on-change* event)
               (on-ctrl-enter event)))))

        on-focus*
        (mf/use-fn
         (mf/deps select-on-focus? on-focus)
         (fn [event]
           (when (fn? on-focus)
             (on-focus event))

           (when ^boolean select-on-focus?
             (let [target (dom/get-target event)]
               (dom/select-text! target)
               ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" dom/prevent-default #js {:once true})))))]

    (mf/use-layout-effect
     nil
     (fn []
       (let [node (mf/ref-val local-ref)]
         (set! (.-height (.-style node)) "0")
         (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px")))))

    [:textarea {:ref local-ref
                :auto-focus autofocus?
                :on-key-down on-key-down
                :on-focus on-focus*
                :on-blur on-blur
                :value value
                :placeholder placeholder
                :on-change on-change*
                :max-length max-length}]))

(mf/defc reply-form
  [{:keys [thread] :as props}]
  (let [show-buttons? (mf/use-state false)
        content       (mf/use-state "")

        disabled? (or (str/blank? @content)
                      (str/empty? @content))

        on-focus
        (mf/use-fn
         #(reset! show-buttons? true))

        on-blur
        (mf/use-fn
         #(reset! show-buttons? false))

        on-change
        (mf/use-fn
         #(reset! content %))

        on-cancel
        (mf/use-fn
         #(do (reset! content "")
              (reset! show-buttons? false)))

        on-submit
        (mf/use-fn
         (mf/deps thread @content)
         (fn []
           (st/emit! (dcm/add-comment thread @content))
           (on-cancel)))

        on-esc
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (on-cancel)
           (st/emit! :interrupt)))]

    [:div {:class (stl/css :reply-form)}
     [:&
      comment-input
      ;;resizing-textarea
      {:value @content
       :placeholder "Reply"
       :on-blur on-blur
       :on-focus on-focus
       :select-on-focus? false
       :on-ctrl-enter on-submit
       :on-change on-change
       :on-esc on-esc
       :max-length 750}]

     (when (or @show-buttons? (seq @content))
       [:div {:class (stl/css :buttons-wrapper)}
        [:input.btn-secondary
         {:type "button"
          :class (stl/css :cancel-btn)
          :value "Cancel"
          :on-click on-cancel}]
        [:input
         {:type "button"
          :class (stl/css-case :post-btn true
                               :global/disabled disabled?)
          :value "Post"
          :on-click on-submit
          :disabled disabled?}]])]))

(mf/defc draft-thread
  [{:keys [draft zoom on-cancel on-submit position-modifier]}]
  (let [mentions-str (mf/use-memo #(rx/subject))
        users (mf/deref refs/current-file-comments-users)
        position (cond-> (:position draft)
                   (some? position-modifier)
                   (gpt/transform position-modifier))
        content  (:content draft)

        pos-x    (* (:x position) zoom)
        pos-y    (* (:y position) zoom)

        disabled? (or (str/blank? content)
                      (str/empty? content))

        on-esc
        (mf/use-fn
         (mf/deps draft)
         (fn [event]
           (dom/stop-propagation event)
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        on-change
        (mf/use-fn
         (mf/deps draft)
         (fn [content]
           (st/emit! (dcm/update-draft-thread {:content content}))))

        on-submit
        (mf/use-fn
         (mf/deps draft)
         (partial on-submit draft))]

    [:& (mf/provider mentions-context) {:value mentions-str}
     [:div
      {:class (stl/css :floating-thread-bubble)
       :data-testid "floating-thread-bubble"
       :style {:top (str pos-y "px")
               :left (str pos-x "px")}
       :on-click dom/stop-propagation}
      "?"]
     [:div {:class (stl/css :thread-content)
            :style {:top (str (- pos-y 24) "px")
                    :left (str (+ pos-x 28) "px")}
            :on-click dom/stop-propagation}
      [:div {:class (stl/css :reply-form)}
       [:& ;;resizing-textarea
        comment-input
        {:placeholder (tr "labels.write-new-comment")
         :value (or content "")
         :autofocus true
         :select-on-focus? false
         :on-esc on-esc
         :on-change on-change
         :on-ctrl-enter on-submit
         :max-length 750}]
       [:div {:class (stl/css :buttons-wrapper)}

        [:input {:on-click on-esc
                 :class (stl/css :cancel-btn)
                 :type "button"
                 :value "Cancel"}]

        [:input {:on-click on-submit
                 :type "button"
                 :value "Post"
                 :class (stl/css-case :post-btn true
                                      :global/disabled disabled?)
                 :disabled disabled?}]]]

      [:& mentions-panel {:users users}]]]))

(mf/defc edit-form
  [{:keys [content on-submit on-cancel] :as props}]
  (let [content (mf/use-state content)

        on-change
        (mf/use-fn
         #(reset! content %))

        on-submit*
        (mf/use-fn
         (mf/deps @content)
         (fn [] (on-submit @content)))

        disabled? (or (str/blank? @content)
                      (str/empty? @content))]

    [:div {:class (stl/css :edit-form)}
     [:& comment-input
      ;;resizing-textarea
      {:value @content
       :autofocus true
       :select-on-focus true
       :select-on-focus? false
       :on-ctrl-enter on-submit*
       :on-change on-change
       :max-length 750}]
     [:div {:class (stl/css :buttons-wrapper)}
      [:input  {:type "button"
                :value "Cancel"
                :class (stl/css :cancel-btn)
                :on-click on-cancel}]
      [:input {:type "button"
               :class (stl/css-case :post-btn true
                                    :global/disabled disabled?)
               :value "Post"
               :on-click on-submit*
               :disabled disabled?}]]]))

(mf/defc comment-item
  [{:keys [comment thread profiles origin] :as props}]
  (let [owner    (get profiles (:owner-id comment))
        profile  (mf/deref refs/profile)
        options  (mf/deref comments-local-options)
        edition? (mf/use-state false)

        comment-elements (mf/use-memo #(parse-comment (:content comment)))

        on-toggle-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/toggle-comment-options comment))))

        on-hide-options
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/hide-comment-options))))

        on-edit-clicked
        (mf/use-fn
         (mf/deps options)
         (fn []
           (st/emit! (dcm/hide-comment-options))
           (reset! edition? true)))

        on-delete-comment
        (mf/use-fn
         (mf/deps comment)
         #(st/emit! (dcm/delete-comment comment)))

        delete-thread
        (mf/use-fn
         (mf/deps thread)
         #(st/emit! (dcm/close-thread)
                    (if (= origin :viewer)
                      (dcm/delete-comment-thread-on-viewer thread)
                      (dcm/delete-comment-thread-on-workspace thread))))

        on-delete-thread
        (mf/use-fn
         (mf/deps thread)
         #(st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-comment-thread.title")
                      :message (tr "modals.delete-comment-thread.message")
                      :accept-label (tr "modals.delete-comment-thread.accept")
                      :on-accept delete-thread})))

        on-submit
        (mf/use-fn
         (mf/deps comment thread)
         (fn [content]
           (reset! edition? false)
           (st/emit! (dcm/update-comment (assoc comment :content content)))))

        on-cancel
        (mf/use-fn #(reset! edition? false))

        toggle-resolved
        (mf/use-fn
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/update-comment-thread (update thread :is-resolved not)))))]

    [:div {:class (stl/css :comment-container)}
     [:div {:class (stl/css :comment)}
      [:div {:class (stl/css :author)}
       [:div {:class (stl/css :avatar)}
        [:img {:src (cfg/resolve-profile-photo-url owner)}]]
       [:div {:class (stl/css :name)}
        [:div {:class (stl/css :fullname)} (:fullname owner)]
        [:div {:class (stl/css :timeago)} (dt/timeago (:modified-at comment))]]

       (when (some? thread)
         [:div {:class (stl/css :options-resolve-wrapper)
                :on-click toggle-resolved}
          [:span {:class (stl/css-case :options-resolve true
                                       :global/checked (:is-resolved thread))} i/tick]])

       (when (= (:id profile) (:id owner))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "labels.options")
                           :on-click on-toggle-options
                           :icon "menu"}])]

      [:div {:class (stl/css :content)}
       (if @edition?
         [:& edit-form {:content (:content comment)
                        :on-submit on-submit
                        :on-cancel on-cancel}]
         (for [[idx {:keys [type content]}] (d/enumerate comment-elements)]
           (case type
             [:span
              {:key idx
               :class (stl/css-case
                       :comment-text (= type :text)
                       :comment-mention (= type :mention))}
              content])))]]

     [:& dropdown {:show (= options (:id comment))
                   :on-close on-hide-options}
      [:ul {:class (stl/css :comment-options-dropdown)}
       [:li {:class (stl/css :context-menu-option)
             :on-click on-edit-clicked}
        (tr "labels.edit")]
       (if thread
         [:li {:class (stl/css :context-menu-option)
               :on-click on-delete-thread}
          (tr "labels.delete-comment-thread")]
         [:li {:class (stl/css :context-menu-option)
               :on-click on-delete-comment}
          (tr "labels.delete-comment")])]]]))

(defn make-comments-ref
  [thread-id]
  (l/derived (l/in [:comments thread-id]) st/state))

(defn- offset-position [position viewport zoom bubble-margin]
  (let [viewport (or viewport {:offset-x 0 :offset-y 0 :width 0 :height 0})
        base-x (+ (* (:x position) zoom) (:offset-x viewport))
        base-y (+ (* (:y position) zoom) (:offset-y viewport))
        w (:width viewport)
        h (:height viewport)
        comment-width 284 ;; TODO: this is the width set via CSS in an outer container…
        ;; We should probably do this in a different way.
        orientation-left? (>= (+ base-x comment-width (:x bubble-margin)) w)
        orientation-top? (>= base-y (/ h 2))
        h-dir (if orientation-left? :left :right)
        v-dir (if orientation-top? :top :bottom)
        x (:x position)
        y (:y position)]
    {:x x :y y :h-dir h-dir :v-dir v-dir}))

(mf/defc thread-comments
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom profiles origin position-modifier viewport]}]
  (let [ref          (mf/use-ref)
        thread-id    (:id thread)
        thread-pos   (:position thread)

        mentions-str (mf/use-memo #(rx/subject))

        base-pos     (cond-> thread-pos
                       (some? position-modifier)
                       (gpt/transform position-modifier))

        max-height   (when (some? viewport) (int (* (:height viewport) 0.75)))
        ;; We should probably look for a better way of doing this.
        bubble-margin {:x 24 :y 0}
        pos          (offset-position base-pos viewport zoom bubble-margin)

        margin-x     (* (:x bubble-margin) (if (= (:h-dir pos) :left) -1 1))
        margin-y     (* (:y bubble-margin) (if (= (:v-dir pos) :top) -1 1))
        pos-x        (+ (* (:x pos) zoom) margin-x)
        pos-y        (- (* (:y pos) zoom) margin-y)

        comments-ref (mf/with-memo [thread-id]
                       (make-comments-ref thread-id))
        comments-map (mf/deref comments-ref)

        comments     (mf/with-memo [comments-map]
                       (->> (vals comments-map)
                            (sort-by :created-at)))

        comment      (first comments)]

    (mf/with-effect [thread-id]
      (st/emit! (dcm/retrieve-comments thread-id)))

    (mf/with-effect [thread-id]
      (st/emit! (dcm/update-comment-thread-status thread-id)))

    (mf/with-layout-effect [thread-pos comments-map]
      (when-let [node (mf/ref-val ref)]
        (dom/scroll-into-view-if-needed! node)))

    [:& (mf/provider mentions-context) {:value mentions-str}
     (when (some? comment)
       [:div {:class (stl/css-case :thread-content true
                                   :thread-content-left (= (:h-dir pos) :left)
                                   :thread-content-top (= (:v-dir pos) :top))
              :id (str "thread-" thread-id)
              :style {:left (str pos-x "px")
                      :top (str pos-y "px")
                      :max-height max-height}
              :on-click dom/stop-propagation}

        [:div {:class (stl/css :comments)}
         [:& comment-item {:comment comment
                          :profiles profiles
                           :thread thread
                           :origin origin}]
         (for [item (rest comments)]
           [:* {:key (dm/str (:id item))}
            [:& comment-item {:comment item
                             :profiles profiles
                              :origin origin}]])]

        [:& reply-form {:thread thread}]

        [:div {:ref ref}]

        [:& mentions-panel {:users users}]])]))

(defn use-buble
  [zoom {:keys [position frame-id]}]
  (let [dragging-ref (mf/use-ref false)
        start-ref    (mf/use-ref nil)

        state        (mf/use-state {:hover false
                                    :new-position-x nil
                                    :new-position-y nil
                                    :new-frame-id frame-id})

        on-pointer-down
        (mf/use-fn
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))))

        on-pointer-up
        (mf/use-fn
         (mf/deps (select-keys @state [:new-position-x :new-position-y :new-frame-id]))
         (fn [_ thread]
           (when (and
                  (some? (:new-position-x @state))
                  (some? (:new-position-y @state)))
             (st/emit! (dwcm/update-comment-thread-position thread [(:new-position-x @state) (:new-position-y @state)])))))

        on-lost-pointer-capture
        (mf/use-fn
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (swap! state assoc :new-position-x nil)
           (swap! state assoc :new-position-y nil)))

        on-pointer-move
        (mf/use-fn
         (mf/deps position zoom)
         (fn [event]
           (when-let [_ (mf/ref-val dragging-ref)]
             (let [start-pt (mf/ref-val start-ref)
                   current-pt (dom/get-client-position event)
                   delta-x (/ (- (:x current-pt) (:x start-pt)) zoom)
                   delta-y (/ (- (:y current-pt) (:y start-pt)) zoom)]
               (swap! state assoc
                      :new-position-x (+ (:x position) delta-x)
                      :new-position-y (+ (:y position) delta-y))))))]

    {:on-pointer-down on-pointer-down
     :on-pointer-up on-pointer-up
     :on-pointer-move on-pointer-move
     :on-lost-pointer-capture on-lost-pointer-capture
     :state state}))

(mf/defc thread-bubble
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom open? on-click origin position-modifier]}]
  (let [pos       (cond-> (:position thread)
                    (some? position-modifier)
                    (gpt/transform position-modifier))

        drag?     (mf/use-ref nil)
        was-open? (mf/use-ref nil)

        {:keys [on-pointer-down
                on-pointer-up
                on-pointer-move
                state
                on-lost-pointer-capture]} (use-buble zoom thread)

        pos-x (* (or (:new-position-x @state) (:x pos)) zoom)
        pos-y (* (or (:new-position-y @state) (:y pos)) zoom)

        on-pointer-down*
        (mf/use-fn
         (mf/deps origin was-open? open? drag? on-pointer-down)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! was-open? open?)
             (when open? (st/emit! (dcm/close-thread)))
             (mf/set-ref-val! drag? false)
             (dom/stop-propagation event)
             (on-pointer-down event))))

        on-pointer-up*
        (mf/use-fn
         (mf/deps origin thread was-open? drag? on-pointer-up)
         (fn [event]
           (when (not= origin :viewer)
             (dom/stop-propagation event)
             (on-pointer-up event thread)

             (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                       (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
               (st/emit! (dcm/open-thread thread))))))

        on-pointer-move*
        (mf/use-fn
         (mf/deps origin drag? on-pointer-move)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! drag? true)
             (dom/stop-propagation event)
             (on-pointer-move event))))

        on-click*
        (mf/use-fn
         (mf/deps origin thread on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (= origin :viewer)
             (on-click thread))))]
    [:div {:style {:top (str pos-y "px")
                   :left (str pos-x "px")}
           :on-pointer-down on-pointer-down*
           :on-pointer-up on-pointer-up*
           :on-pointer-move on-pointer-move*
           :on-click on-click*
           :on-lost-pointer-capture on-lost-pointer-capture
           :data-testid "floating-thread-bubble"
           :class (stl/css-case
                   :floating-thread-bubble true
                   :resolved (:is-resolved thread)
                   :unread (pos? (:count-unread-comments thread)))}
     [:span (:seqn thread)]]))

(mf/defc comment-thread
  [{:keys [item profiles on-click]}]
  (let [owner (get profiles (:owner-id item))
        comment-elements (mf/use-memo #(parse-comment (:content item)))

        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    [:div {:class (stl/css :comment)
           :on-click on-click*}
     [:div {:class (stl/css :author)}
      [:div {:class (stl/css-case :thread-bubble true
                                  :resolved (:is-resolved item)
                                  :unread (pos? (:count-unread-comments item)))}
       (:seqn item)]
      [:div {:class (stl/css :avatar)}
       [:img {:src (cfg/resolve-profile-photo-url owner)}]]
      [:div {:class (stl/css :name)}
       [:div {:class (stl/css :fullname)} (:fullname owner)]
       [:div {:class (stl/css :timeago)} (dt/timeago (:modified-at item))]]]
     [:div {:class (stl/css :content)}
      (for [[idx {:keys [type content]}] (d/enumerate comment-elements)]
        (case type
          [:span
           {:key idx
            :class (stl/css-case
                    :comment-text (= type :text)
                    :comment-mention (= type :mention))}
           content]))]


     [:div {:class (stl/css :replies)}
      (let [unread (:count-unread-comments item ::none)
            total  (:count-comments item 1)]
        [:*
         (when (> total 1)
           (if (= total 2)
             [:span {:class (stl/css :total-replies)} "1 reply"]
             [:span {:class (stl/css :total-replies)} (str (dec total) " replies")]))

         (when (and (> total 1) (> unread 0))
           (if (= unread 1)
             [:span {:class (stl/css :new-replies)} "1 new reply"]
             [:span {:class (stl/css :new-replies)} (str unread " new replies")]))])]]))

(mf/defc comment-thread-group
  [{:keys [group profiles on-thread-click]}]
  [:div {:class (stl/css :thread-group)}
   (if (:file-name group)
     [:div {:class (stl/css :section-title)
            :title (str (:file-name group) ", " (:page-name group))}
      [:span {:class (stl/css :file-name)} (:file-name group) ", "]
      [:span {:class (stl/css :page-name)} (:page-name group)]]

     [:div {:class (stl/css :section-title)
            :title (:page-name group)}
      [:span {:class (stl/css :icon)} i/document]
      [:span {:class (stl/css :page-name)} (:page-name group)]])

   [:div {:class (stl/css :threads)}
    (for [item (:items group)]
      [:& comment-thread
       {:item item
        :on-click on-thread-click
        :profiles profiles
        :key (:id item)}])]])
