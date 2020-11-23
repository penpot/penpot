;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.comments
  (:require
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.data.modal :as modal]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.util.time :as dt]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.i18n :as i18n :refer [t tr]]
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
               (not (empty? @content)))
       [:div.buttons
        [:input.btn-primary {:type "button" :value "Post" :on-click on-submit}]
        [:input.btn-secondary {:type "button" :value "Cancel" :on-click on-cancel}]])]))

(mf/defc draft-thread
  [{:keys [draft zoom on-cancel on-submit] :as props}]
  (let [position (:position draft)
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
                              :on-esc on-esc
                              :on-change on-change}]
       [:div.buttons
        [:input.btn-primary
         {:on-click on-submit
          :type "button"
          :value "Post"}]
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
  [{:keys [comment thread users] :as props}]
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
         (st/emitf (dcm/delete-comment comment)))

        delete-thread
        (mf/use-callback
         (mf/deps thread)
         (st/emitf (dcm/close-thread)
                   (dcm/delete-comment-thread thread)))


        on-delete-thread
        (mf/use-callback
         (mf/deps thread)
         (st/emitf (modal/show
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
        [:img {:src (cfg/resolve-media-path (:photo owner))}]]
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
  [{:keys [thread zoom users]}]
  (let [ref   (mf/use-ref)
        pos   (:position thread)
        pos-x (+ (* (:x pos) zoom) 14)
        pos-y (- (* (:y pos) zoom) 14)

        comments-ref (mf/use-memo (mf/deps thread) #(comments-ref thread))
        comments-map (mf/deref comments-ref)
        comments     (->> (vals comments-map)
                          (sort-by :created-at))
        comment      (first comments)]

    (mf/use-layout-effect
     (mf/deps thread)
     (st/emitf (dcm/retrieve-comments (:id thread))))

    (mf/use-effect
     (mf/deps thread)
     (st/emitf (dcm/update-comment-thread-status thread)))

    (mf/use-layout-effect
     (mf/deps thread comments-map)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (.scrollIntoViewIfNeeded ^js node))))

    [:div.thread-content
     {:style {:top (str pos-y "px")
              :left (str pos-x "px")}
      :on-click dom/stop-propagation}

     [:div.comments
      [:& comment-item {:comment comment
                        :users users
                        :thread thread}]
      (for [item (rest comments)]
        [:*
         [:hr]
         [:& comment-item {:comment item :users users}]])
      [:div {:ref ref}]]
     [:& reply-form {:thread thread}]]))

(mf/defc thread-bubble
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom open? on-click] :as params}]
  (let [pos   (:position thread)
        pos-x (* (:x pos) zoom)
        pos-y (* (:y pos) zoom)
        on-click* (fn [event]
                    (dom/stop-propagation event)
                    (on-click thread))]

    [:div.thread-bubble
     {:style {:top (str pos-y "px")
              :left (str pos-x "px")}
      :on-mouse-down (fn [event]
                       (dom/prevent-default event))
      :class (dom/classnames
              :resolved (:is-resolved thread)
              :unread (pos? (:count-unread-comments thread)))
      :on-click on-click*}
     [:span (:seqn thread)]]))

(mf/defc comment-thread
  [{:keys [item users on-click] :as props}]
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
       [:img {:src (cfg/resolve-media-path (:photo owner))}]]
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
