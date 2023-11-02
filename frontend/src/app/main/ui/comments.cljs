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
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc resizing-textarea
  {::mf/wrap-props false }
  [props]
  (let [value            (d/nilv (unchecked-get props "value") "")
        on-focus         (unchecked-get props "on-focus")
        on-blur          (unchecked-get props "on-blur")
        placeholder      (unchecked-get props "placeholder")
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

    [:textarea
     {:ref local-ref
      :auto-focus autofocus?
      :on-key-down on-key-down
      :on-focus on-focus*
      :on-blur on-blur
      :value value
      :placeholder placeholder
      :on-change on-change*}]))

(mf/defc reply-form
  [{:keys [thread] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        show-buttons? (mf/use-state false)
        content       (mf/use-state "")

        disabled? (or (fm/all-spaces? @content)
                      (str/empty-or-nil? @content))

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
           (on-cancel)))]
    (if new-css-system
      [:div {:class (stl/css :reply-form)}
       [:& resizing-textarea {:value @content
                              :placeholder "Reply"
                              :on-blur on-blur
                              :on-focus on-focus
                              :select-on-focus? false
                              :on-ctrl-enter on-submit
                              :on-change on-change}]
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
            :disabled disabled?}]])]


      [:div.reply-form
       [:& resizing-textarea {:value @content
                              :placeholder "Reply"
                              :on-blur on-blur
                              :on-focus on-focus
                              :on-ctrl-enter on-submit
                              :on-change on-change}]
       (when (or @show-buttons? (seq @content))
         [:div.buttons
          [:input.btn-primary
           {:type "button"
            :value "Post"
            :on-click on-submit
            :disabled disabled?}]
          [:input.btn-secondary
           {:type "button"
            :value "Cancel"
            :on-click on-cancel}]])])))

(mf/defc draft-thread
  [{:keys [draft zoom on-cancel on-submit position-modifier]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        position (cond-> (:position draft)
                   (some? position-modifier)
                   (gpt/transform position-modifier))
        content  (:content draft)

        pos-x    (* (:x position) zoom)
        pos-y    (* (:y position) zoom)

        disabled? (or (fm/all-spaces? content)
                      (str/empty-or-nil? content))

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


    (if new-css-system
      [:*
       [:div
        {:class (stl/css :floating-thread-bubble)
         :style {:top (str pos-y "px")
                 :left (str pos-x "px")}
         :on-click dom/stop-propagation}
        "?"]
       [:div {:class (stl/css :thread-content)
              :style {:top (str (- pos-y 24) "px")
                      :left (str (+ pos-x 28) "px")}
              :on-click dom/stop-propagation}
        [:div {:class (stl/css :reply-form)}
         [:& resizing-textarea {:placeholder (tr "labels.write-new-comment")
                                :value (or content "")
                                :autofocus true
                                :select-on-focus? false
                                :on-esc on-esc
                                :on-change on-change
                                :on-ctrl-enter on-submit}]
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
                   :disabled disabled?}]]]]]

      [:*
       [:div.thread-bubble
        {:style {:top (str pos-y "px")
                 :left (str pos-x "px")}
         :on-click dom/stop-propagation}
        [:span "?"]]
       [:div.thread-content
        {:style {:top (str (- pos-y 14) "px")
                 :left (str (+ pos-x 14) "px")}
         :on-click dom/stop-propagation}
        [:div.reply-form
         [:& resizing-textarea {:placeholder (tr "labels.write-new-comment")
                                :value (or content "")
                                :autofocus true
                                :on-esc on-esc
                                :on-ctrl-enter on-submit
                                :on-change on-change}]
         [:div.buttons
          [:input.btn-primary
           {:on-click on-submit
            :type "button"
            :value "Post"
            :disabled disabled?}]
          [:input.btn-secondary
           {:on-click on-esc
            :type "button"
            :value "Cancel"}]]]]])))

(mf/defc edit-form
  [{:keys [content on-submit on-cancel] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        content (mf/use-state content)

        on-change
        (mf/use-fn
         #(reset! content %))

        on-submit*
        (mf/use-fn
         (mf/deps @content)
         (fn [] (on-submit @content)))

        disabled? (or (fm/all-spaces? @content)
                      (str/empty-or-nil? @content))]

    (if new-css-system
      [:div {:class (stl/css :edit-form)}
       [:& resizing-textarea {:value @content
                              :autofocus true
                              :select-on-focus true
                              :select-on-focus? false
                              :on-ctrl-enter on-submit*
                              :on-change on-change}]
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
                 :disabled disabled?}]]]


      [:div.reply-form.edit-form
       [:& resizing-textarea {:value @content
                              :autofocus true
                              :select-on-focus true
                              :on-ctrl-enter on-submit*
                              :on-change on-change}]
       [:div.buttons
        [:input.btn-primary {:type "button"
                             :value "Post"
                             :on-click on-submit*
                             :disabled disabled?}]
        [:input.btn-secondary {:type "button" :value "Cancel" :on-click on-cancel}]]])))

(mf/defc comment-item
  [{:keys [comment thread users origin] :as props}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        owner    (get users (:owner-id comment))
        profile  (mf/deref refs/profile)
        options  (mf/use-state false)
        edition? (mf/use-state false)

        on-toggle-options
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! options not)))

        on-hide-options
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! options false)))

        on-edit-clicked
        (mf/use-fn
         (fn []
           (reset! options false)
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

    (if new-css-system
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
                                         :global/checked (:is-resolved thread))} i/tick-refactor]])

         (when (= (:id profile) (:id owner))
           [:div {:class (stl/css :options)
                  :on-click on-toggle-options}
            i/menu-refactor])]

        [:div {:class (stl/css :content)}
         (if @edition?
           [:& edit-form {:content (:content comment)
                          :on-submit on-submit
                          :on-cancel on-cancel}]
           [:span {:class (stl/css :text)} (:content comment)])]]

       [:& dropdown {:show @options
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
            (tr "labels.delete-comment")])]]]


      [:div.comment-container
       [:div.comment
        [:div.author
         [:div.avatar
          [:img {:src (cfg/resolve-profile-photo-url owner)}]]
         [:div.name
          [:div.fullname (:fullname owner)]
          [:div.timeago (dt/timeago (:modified-at comment))]]

         (when (some? thread)
           [:div.options-resolve {:on-click toggle-resolved}
            (if (:is-resolved thread)
              [:span i/checkbox-checked]
              [:span i/checkbox-unchecked])])

         (when (= (:id profile) (:id owner))
           [:div.options
            [:div.options-icon {:on-click on-toggle-options} i/actions]])]

        [:div.content
         (if @edition?
           [:& edit-form {:content (:content comment)
                          :on-submit on-submit
                          :on-cancel on-cancel}]
           [:span.text (:content comment)])]]

       [:& dropdown {:show @options
                     :on-close on-hide-options}
        [:ul.dropdown.comment-options-dropdown
         [:li {:on-click on-edit-clicked} (tr "labels.edit")]
         (if thread
           [:li {:on-click on-delete-thread} (tr "labels.delete-comment-thread")]
           [:li {:on-click on-delete-comment} (tr "labels.delete-comment")])]]])))

(defn make-comments-ref
  [thread-id]
  (l/derived (l/in [:comments thread-id]) st/state))

(mf/defc thread-comments
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom users origin position-modifier]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        ref          (mf/use-ref)


        thread-id    (:id thread)
        thread-pos   (:position thread)

        pos          (cond-> thread-pos
                       (some? position-modifier)
                       (gpt/transform position-modifier))

        pos-x        (if new-css-system
                       (+ (* (:x pos) zoom) 24)
                       (+ (* (:x pos) zoom) 14))
        pos-y        (if new-css-system
                       (- (* (:y pos) zoom) 28)
                       (- (* (:y pos) zoom) 14))


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
    (if new-css-system
      (when (some? comment)
        [:div {:class (stl/css :thread-content)
               :style {:top (str pos-y "px")
                       :left (str pos-x "px")}
               :on-click dom/stop-propagation}

         [:div {:class (stl/css :comments)}
          [:& comment-item {:comment comment
                            :users users
                            :thread thread
                            :origin origin}]
          (for [item (rest comments)]
            [:* {:key (dm/str (:id item))}
             [:& comment-item {:comment item
                               :users users
                               :origin origin}]])
          [:div {:ref ref}]]
         [:& reply-form {:thread thread}]])


      (when (some? comment)
        [:div.thread-content
         {:style {:top (str pos-y "px")
                  :left (str pos-x "px")}
          :on-click dom/stop-propagation}

         [:div.comments
          [:& comment-item {:comment comment
                            :users users
                            :thread thread
                            :origin origin}]
          (for [item (rest comments)]
            [:* {:key (dm/str (:id item))}
             [:hr]
             [:& comment-item {:comment item
                               :users users
                               :origin origin}]])
          [:div {:ref ref}]]
         [:& reply-form {:thread thread}]]))))

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
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        pos       (cond-> (:position thread)
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
    (if new-css-system
      [:div {:style {:top (str pos-y "px")
                     :left (str pos-x "px")}
             :on-pointer-down on-pointer-down*
             :on-pointer-up on-pointer-up*
             :on-pointer-move on-pointer-move*
             :on-click on-click*
             :on-lost-pointer-capture on-lost-pointer-capture
             :class (stl/css-case
                     :floating-thread-bubble true
                     :resolved (:is-resolved thread)
                     :unread (pos? (:count-unread-comments thread)))}
       [:span (:seqn thread)]]

      [:div.thread-bubble
       {:style {:top (str pos-y "px")
                :left (str pos-x "px")}
        :on-pointer-down on-pointer-down*
        :on-pointer-up on-pointer-up*
        :on-pointer-move on-pointer-move*
        :on-click on-click*
        :on-lost-pointer-capture on-lost-pointer-capture
        :class (dom/classnames
                :resolved (:is-resolved thread)
                :unread (pos? (:count-unread-comments thread)))}
       [:span (:seqn thread)]])))

(mf/defc comment-thread
  [{:keys [item users on-click]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        owner (get users (:owner-id item))
        on-click*
        (mf/use-fn
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

    (if new-css-system
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
        (:content item)]
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
               [:span {:class (stl/css :new-replies)} (str unread " new replies")]))])]]


      [:div.comment {:on-click on-click*}
       [:div.author
        [:div.thread-bubble
         {:class (dom/classnames
                  :resolved (:is-resolved item)
                  :unread (pos? (:count-unread-comments item)))}
         (:seqn item)]
        [:div.avatar
         [:img {:src (cfg/resolve-profile-photo-url owner)}]]
        [:div.name
         [:div.fullname (:fullname owner) ", "]
         [:div.timeago (dt/timeago (:modified-at item))]]]
       [:div.content
        [:span.text (:content item)]]
       [:div.content.replies
        (let [unread (:count-unread-comments item ::none)
              total  (:count-comments item 1)]
          [:*
           (when (> total 1)
             (if (= total 2)
               [:span.total-replies "1 reply"]
               [:span.total-replies (str (dec total) " replies")]))

           (when (and (> total 1) (> unread 0))
             (if (= unread 1)
               [:span.new-replies "1 new reply"]
               [:span.new-replies (str unread " new replies")]))])]])))

(mf/defc comment-thread-group
  [{:keys [group users on-thread-click]}]
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:div {:class (stl/css :thread-group)}
       (if (:file-name group)
         [:div {:class (stl/css :section-title)}
          [:span {:class (stl/css :file-name)} (:file-name group) ", "]
          [:span {:class (stl/css :page-name)} (:page-name group)]]

         [:div {:class (stl/css :section-title)}
          [:span {:class (stl/css :icon)} i/document-refactor]
          [:span {:class (stl/css :page-name)} (:page-name group)]])

       [:div {:class (stl/css :threads)}
        (for [item (:items group)]
          [:& comment-thread
           {:item item
            :on-click on-thread-click
            :users users
            :key (:id item)}])]]


      [:div.thread-group
       (if (:file-name group)
         [:div.section-title
          [:span.label.filename (:file-name group) ", "]
          [:span.label (:page-name group)]]
         [:div.section-title
          [:span.icon i/file-html]
          [:span.label (:page-name group)]])
       [:div.threads
        (for [item (:items group)]
          [:& comment-thread
           {:item item
            :on-click on-thread-click
            :users users
            :key (:id item)}])]])))
