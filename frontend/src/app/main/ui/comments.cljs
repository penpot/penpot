;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.comments
  (:require
   [app.common.geom.point :as gpt]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(mf/defc resizing-textarea
  {::mf/wrap-props false}
  [props]
  (let [value       (obj/get props "value" "")
        on-focus    (obj/get props "on-focus")
        on-blur     (obj/get props "on-blur")
        placeholder (obj/get props "placeholder")
        on-change   (obj/get props "on-change")
        on-esc      (obj/get props "on-esc")
        autofocus?  (obj/get props "autofocus")

        ref         (mf/use-ref)

        on-key-down
        (mf/use-callback
         (fn [event]
           (when (and (kbd/esc? event)
                      (fn? on-esc))
             (on-esc event))))

        on-change*
        (mf/use-callback
         (mf/deps on-change)
         (fn [event]
           (let [content (dom/get-target-val event)]
             (on-change content))))]


    (mf/use-layout-effect
     nil
     (fn []
       (let [node (mf/ref-val ref)]
         (set! (.-height (.-style node)) "0")
         (set! (.-height (.-style node)) (str (+ 2 (.-scrollHeight node)) "px")))))

    [:textarea
     {:ref ref
      :auto-focus autofocus?
      :on-key-down on-key-down
      :on-focus on-focus
      :on-blur on-blur
      :value value
      :placeholder placeholder
      :on-change on-change*}]))

(mf/defc reply-form
  [{:keys [thread] :as props}]
  (let [show-buttons? (mf/use-state false)
        content       (mf/use-state "")

        on-focus
        (mf/use-callback
         #(reset! show-buttons? true))

        on-blur
        (mf/use-callback
         #(reset! show-buttons? false))

        on-change
        (mf/use-callback
         #(reset! content %))

        on-cancel
        (mf/use-callback
         #(do (reset! content "")
              (reset! show-buttons? false)))

        on-submit
        (mf/use-callback
         (mf/deps thread @content)
         (fn []
           (st/emit! (dcm/add-comment thread @content))
           (on-cancel)))]

    [:div.reply-form
     [:& resizing-textarea {:value @content
                            :placeholder "Reply"
                            :on-blur on-blur
                            :on-focus on-focus
                            :on-change on-change}]
     (when (or @show-buttons?
               (seq @content))
       [:div.buttons
        [:input.btn-primary {:type "button" :value "Post" :on-click on-submit :disabled (str/empty-or-nil? @content)}]
        [:input.btn-secondary {:type "button" :value "Cancel" :on-click on-cancel}]])]))

(mf/defc draft-thread
  [{:keys [draft zoom on-cancel on-submit position-modifier]}]
  (let [position (cond-> (:position draft)
                   (some? position-modifier)
                   (gpt/transform position-modifier))
        content  (:content draft)
        pos-x    (* (:x position) zoom)
        pos-y    (* (:y position) zoom)

        on-esc
        (mf/use-callback
         (mf/deps draft)
         (fn [event]
           (dom/stop-propagation event)
           (if (fn? on-cancel)
             (on-cancel)
             (st/emit! :interrupt))))

        on-change
        (mf/use-callback
         (mf/deps draft)
         (fn [content]
           (st/emit! (dcm/update-draft-thread {:content content}))))

        on-submit
        (mf/use-callback
         (mf/deps draft)
         (partial on-submit draft))]

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
                              :on-change on-change}]
       [:div.buttons
        [:input.btn-primary
         {:on-click on-submit
          :type "button"
          :value "Post"
          :disabled (str/empty-or-nil? content)}]
        [:input.btn-secondary
         {:on-click on-esc
          :type "button"
          :value "Cancel"}]]]]]))

(mf/defc edit-form
  [{:keys [content on-submit on-cancel] :as props}]
  (let [content (mf/use-state content)

        on-change
        (mf/use-callback
         #(reset! content %))

        on-submit*
        (mf/use-callback
         (mf/deps @content)
         (fn [] (on-submit @content)))]

    [:div.reply-form.edit-form
     [:& resizing-textarea {:value @content
                            :on-change on-change}]
     [:div.buttons
      [:input.btn-primary {:type "button" :value "Post" :on-click on-submit*}]
      [:input.btn-secondary {:type "button" :value "Cancel" :on-click on-cancel}]]]))

(mf/defc comment-item
  [{:keys [comment thread users origin] :as props}]
  (let [owner    (get users (:owner-id comment))
        profile  (mf/deref refs/profile)
        options  (mf/use-state false)
        edition? (mf/use-state false)

        on-show-options
        (mf/use-callback #(reset! options true))

        on-hide-options
        (mf/use-callback #(reset! options false))

        on-edit-clicked
        (mf/use-callback
         (fn []
           (reset! options false)
           (reset! edition? true)))

        on-delete-comment
        (mf/use-callback
         (mf/deps comment)
         #(st/emit! (dcm/delete-comment comment)))

        delete-thread
        (mf/use-callback
         (mf/deps thread)
         #(st/emit! (dcm/close-thread)
                    (if (= origin :viewer)
                      (dcm/delete-comment-thread-on-viewer thread)
                      (dcm/delete-comment-thread-on-workspace thread))))


        on-delete-thread
        (mf/use-callback
         (mf/deps thread)
         #(st/emit! (modal/show
                      {:type :confirm
                       :title (tr "modals.delete-comment-thread.title")
                       :message (tr "modals.delete-comment-thread.message")
                       :accept-label (tr "modals.delete-comment-thread.accept")
                       :on-accept delete-thread})))

        on-submit
        (mf/use-callback
         (mf/deps comment thread)
         (fn [content]
           (reset! edition? false)
           (st/emit! (dcm/update-comment (assoc comment :content content)))))

        on-cancel
        (mf/use-callback #(reset! edition? false))

        toggle-resolved
        (mf/use-callback
         (mf/deps thread)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (dcm/update-comment-thread (update thread :is-resolved not)))))]

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
          [:div.options-icon {:on-click on-show-options} i/actions]])]

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
         [:li {:on-click on-delete-comment} (tr "labels.delete-comment")])]]]))

(defn comments-ref
  [{:keys [id] :as thread}]
  (l/derived (l/in [:comments id]) st/state))

(mf/defc thread-comments
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom users origin position-modifier]}]
  (let [ref   (mf/use-ref)
        pos   (cond-> (:position thread)
                (some? position-modifier)
                (gpt/transform position-modifier))

        pos-x (+ (* (:x pos) zoom) 14)
        pos-y (- (* (:y pos) zoom) 14)

        comments-ref (mf/use-memo (mf/deps thread) #(comments-ref thread))
        comments-map (mf/deref comments-ref)
        comments     (->> (vals comments-map)
                          (sort-by :created-at))
        comment      (first comments)]

    (mf/use-layout-effect
     (mf/deps thread)
     #(st/emit! (dcm/retrieve-comments (:id thread))))

    (mf/use-effect
     (mf/deps thread)
     #(st/emit! (dcm/update-comment-thread-status thread)))

    (mf/use-layout-effect
     (mf/deps thread comments-map)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (dom/scroll-into-view-if-needed! node))))

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
          [:*
           [:hr]
           [:& comment-item {:comment item
                             :users users
                             :origin origin}]])
        [:div {:ref ref}]]
       [:& reply-form {:thread thread}]])))

(defn use-buble
  [zoom {:keys [position frame-id]}]
  (let [dragging-ref (mf/use-ref false)
        start-ref (mf/use-ref nil)

        state (mf/use-state {:hover false
                             :new-position-x nil
                             :new-position-y nil
                             :new-frame-id frame-id})

        on-pointer-down
        (mf/use-callback
         (fn [event]
           (dom/capture-pointer event)
           (mf/set-ref-val! dragging-ref true)
           (mf/set-ref-val! start-ref (dom/get-client-position event))))

        on-pointer-up
        (mf/use-callback
         (mf/deps (select-keys @state [:new-position-x :new-position-y :new-frame-id]))
         (fn [_ thread]
           (when (and
                  (some? (:new-position-x @state))
                  (some? (:new-position-y @state)))
             (st/emit! (dwcm/update-comment-thread-position thread [(:new-position-x @state) (:new-position-y @state)])))))

        on-lost-pointer-capture
        (mf/use-callback
         (fn [event]
           (dom/release-pointer event)
           (mf/set-ref-val! dragging-ref false)
           (mf/set-ref-val! start-ref nil)
           (swap! state assoc :new-position-x nil)
           (swap! state assoc :new-position-y nil)))

        on-mouse-move
        (mf/use-callback
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
     :on-mouse-move on-mouse-move
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
                on-mouse-move
                state
                on-lost-pointer-capture]} (use-buble zoom thread)

        pos-x (* (or (:new-position-x @state) (:x pos)) zoom)
        pos-y (* (or (:new-position-y @state) (:y pos)) zoom)

        on-pointer-down*
        (mf/use-callback
         (mf/deps origin was-open? open? drag? on-pointer-down)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! was-open? open?)
             (when open? (st/emit! (dcm/close-thread)))
             (mf/set-ref-val! drag? false)
             (dom/stop-propagation event)
             (on-pointer-down event))))

        on-pointer-up*
        (mf/use-callback
         (mf/deps origin thread was-open? drag? on-pointer-up)
         (fn [event]
           (when (not= origin :viewer)
             (dom/stop-propagation event)
             (on-pointer-up event thread)

             (when (or (and (mf/ref-val was-open?) (mf/ref-val drag?))
                       (and (not (mf/ref-val was-open?)) (not (mf/ref-val drag?))))
               (st/emit! (dcm/open-thread thread))))))

        on-mouse-move*
        (mf/use-callback
         (mf/deps origin drag? on-mouse-move)
         (fn [event]
           (when (not= origin :viewer)
             (mf/set-ref-val! drag? true)
             (dom/stop-propagation event)
             (on-mouse-move event))))

        on-click*
        (mf/use-callback
         (mf/deps origin thread on-click)
         (fn [event]
           (dom/stop-propagation event)
           (when (= origin :viewer)
             (on-click thread))))]

    [:div.thread-bubble
     {:style {:top (str pos-y "px")
              :left (str pos-x "px")}
      :on-pointer-down on-pointer-down*
      :on-pointer-up on-pointer-up*
      :on-mouse-move on-mouse-move*
      :on-click on-click*
      :on-lost-pointer-capture on-lost-pointer-capture
      :class (dom/classnames
              :resolved (:is-resolved thread)
              :unread (pos? (:count-unread-comments thread)))}
     [:span (:seqn thread)]]))

(mf/defc comment-thread
  [{:keys [item users on-click]}]
  (let [owner (get users (:owner-id item))
        on-click*
        (mf/use-callback
         (mf/deps item)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (when (fn? on-click)
             (on-click item))))]

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
             [:span.new-replies (str unread " new replies")]))])]]))

(mf/defc comment-thread-group
  [{:keys [group users on-thread-click]}]
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
        :key (:id item)}])]])
