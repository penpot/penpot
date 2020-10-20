;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.comments
  (:refer-clojure :exclude [comment])
  (:require
   [app.config :as cfg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.data.modal :as modal]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.util.time :as dt]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [beicon.core :as rx]
   [app.util.i18n :as i18n :refer [t tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(declare group-threads-by-page)
(declare apply-filters)

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
        ;; state       (mf/use-state value)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
           (st/emit! (dwcm/add-comment thread @content))
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
        [:input.btn-warning {:type "button" :value "Cancel" :on-click on-cancel}]])]))

(mf/defc draft-thread
  [{:keys [draft zoom] :as props}]
  (let [position (:position draft)
        content  (:content draft)
        pos-x    (* (:x position) zoom)
        pos-y    (* (:y position) zoom)

        on-esc
        (mf/use-callback
         (mf/deps draft)
         (st/emitf :interrupt))

        on-change
        (mf/use-callback
         (mf/deps draft)
         (fn [content]
           (st/emit! (dwcm/update-draft-thread (assoc draft :content content)))))

        on-submit
        (mf/use-callback
         (mf/deps draft)
         (st/emitf (dwcm/create-thread draft)))]

    [:*
     [:div.thread-bubble
      {:style {:top (str pos-y "px")
               :left (str pos-x "px")}}
      [:span "?"]]
     [:div.thread-content
      {:style {:top (str (- pos-y 14) "px")
               :left (str (+ pos-x 14) "px")}}
      [:div.reply-form
       [:& resizing-textarea {:placeholder "Write new comment"
                              :value content
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
      [:input.btn-warning {:type "button" :value "Cancel" :on-click on-cancel}]]]))


(mf/defc comment-item
  [{:keys [comment thread] :as props}]
  (let [profile  (get @refs/workspace-users (:owner-id comment))
        options? (mf/use-state false)
        edition? (mf/use-state false)

        on-edit-clicked
        (mf/use-callback
         (fn []
           (reset! options? false)
           (reset! edition? true)))

        on-delete-comment
        (mf/use-callback
         (mf/deps comment)
         (st/emitf (dwcm/delete-comment comment)))

        delete-thread
        (mf/use-callback
         (mf/deps thread)
         (st/emitf (dwcm/close-thread)
                   (dwcm/delete-comment-thread thread)))


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
           (st/emit! (dwcm/update-comment (assoc comment :content content)))))

        on-cancel
        (mf/use-callback #(reset! edition? false))

        toggle-resolved
        (mf/use-callback
         (mf/deps thread)
         (st/emitf (dwcm/update-comment-thread (update thread :is-resolved not))))]

    [:div.comment-container
     [:div.comment
      [:div.author
       [:div.avatar
        [:img {:src (cfg/resolve-media-path (:photo profile))}]]
       [:div.name
        [:div.fullname (:fullname profile)]
        [:div.timeago (dt/timeago (:modified-at comment))]]

       (when (some? thread)
         [:div.options-resolve {:on-click toggle-resolved}
          (if (:is-resolved thread)
            [:span i/checkbox-checked]
            [:span i/checkbox-unchecked])])

       [:div.options
        [:div.options-icon {:on-click #(swap! options? not)} i/actions]]]

      [:div.content
       (if @edition?
         [:& edit-form {:content (:content comment)
                        :on-submit on-submit
                        :on-cancel on-cancel}]
         [:span.text (:content comment)])]]

     [:& dropdown {:show @options?
                   :on-close identity}
      [:ul.dropdown.comment-options-dropdown
       [:li {:on-click on-edit-clicked} "Edit"]
       (if thread
         [:li {:on-click on-delete-thread} "Delete thread"]
         [:li {:on-click on-delete-comment} "Delete comment"])]]]))

(defn comments-ref
  [thread-id]
  (l/derived (l/in [:comments thread-id]) st/state))

(mf/defc thread-comments
  [{:keys [thread zoom]}]
  (let [ref   (mf/use-ref)
        pos   (:position thread)
        pos-x (+ (* (:x pos) zoom) 14)
        pos-y (- (* (:y pos) zoom) 14)


        comments-ref (mf/use-memo (mf/deps (:id thread)) #(comments-ref (:id thread)))
        comments-map (mf/deref comments-ref)
        comments     (->> (vals comments-map)
                          (sort-by :created-at))
        comment     (first comments)]

    (mf/use-effect
     (st/emitf (dwcm/update-comment-thread-status thread)))

    (mf/use-effect
     (mf/deps thread)
     (st/emitf (dwcm/retrieve-comments (:id thread))))

    (mf/use-layout-effect
     (mf/deps thread comments-map)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (.scrollIntoView ^js node))))

    [:div.thread-content
     {:style {:top (str pos-y "px")
              :left (str pos-x "px")}}

     [:div.comments
      [:& comment-item {:comment comment
                        :thread thread}]
      (for [item (rest comments)]
        [:*
         [:hr]
         [:& comment-item {:comment item}]])
      [:div {:ref ref}]]
     [:& reply-form {:thread thread}]]))

(mf/defc thread-bubble
  {::mf/wrap [mf/memo]}
  [{:keys [thread zoom open?] :as params}]
  (let [pos   (:position thread)
        pos-x (* (:x pos) zoom)
        pos-y (* (:y pos) zoom)

        on-open-toggle
        (mf/use-callback
         (mf/deps thread open?)
         (fn []
           (if open?
             (st/emit! (dwcm/close-thread))
             (st/emit! (dwcm/open-thread thread)))))]

    [:div.thread-bubble
     {:style {:top (str pos-y "px")
              :left (str pos-x "px")}
      :class (dom/classnames
              :resolved (:is-resolved thread)
              :unread (pos? (:count-unread-comments thread)))
      :on-click on-open-toggle}
     [:span (:seqn thread)]]))

(def threads-ref
  (l/derived :comment-threads st/state))

(def workspace-comments-ref
  (l/derived :workspace-comments st/state))

(mf/defc comments-layer
  [{:keys [vbox vport zoom file-id page-id] :as props}]
  (let [pos-x       (* (- (:x vbox)) zoom)
        pos-y       (* (- (:y vbox)) zoom)
        profile     (mf/deref refs/profile)
        local       (mf/deref workspace-comments-ref)
        threads-map (mf/deref threads-ref)
        threads     (->> (vals threads-map)
                         (filter #(= (:page-id %) page-id))
                         (apply-filters local profile))]

    (mf/use-effect
     (mf/deps file-id)
     (fn []
       (st/emit! (dwcm/initialize-comments file-id))
       (fn []
         (st/emit! ::dwcm/finalize))))

    [:div.workspace-comments
     {:style {:width (str (:width vport) "px")
              :height (str (:height vport) "px")}}
     [:div.threads {:style {:transform (str/format "translate(%spx, %spx)" pos-x pos-y)}}
      (for [item threads]
        [:& thread-bubble {:thread item
                           :zoom zoom
                           :open? (= (:id item) (:open local))
                           :key (:seqn item)}])

      (when-let [id (:open local)]
        (when-let [thread (get threads-map id)]
          [:& thread-comments {:thread thread
                               :zoom zoom}]))

      (when-let [draft (:draft local)]
        [:& draft-thread {:draft draft :zoom zoom}])]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidebar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc sidebar-group-item
  [{:keys [item] :as props}]
  (let [profile (get @refs/workspace-users (:owner-id item))
        on-click
        (mf/use-callback
         (mf/deps item)
         (st/emitf (dwcm/center-to-comment-thread item)
                   (dwcm/open-thread item)))]

    [:div.comment {:on-click on-click}
     [:div.author
      [:div.thread-bubble
       {:class (dom/classnames
                :resolved (:is-resolved item)
                :unread (pos? (:count-unread-comments item)))}
       (:seqn item)]
      [:div.avatar
       [:img {:src (cfg/resolve-media-path (:photo profile))}]]
      [:div.name
       [:div.fullname (:fullname profile) ", "]
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

(defn page-name-ref
  [id]
  (l/derived (l/in [:workspace-data :pages-index id :name]) st/state))

(mf/defc sidebar-item
  [{:keys [group]}]
  (let [page-name-ref (mf/use-memo (mf/deps (:page-id group)) #(page-name-ref (:page-id group)))
        page-name     (mf/deref page-name-ref)]
    [:div.page-section
     [:div.section-title
      [:span.icon i/file-html]
      [:span.label page-name]]
     [:div.comments-container
      (for [item (:items group)]
        [:& sidebar-group-item {:item item :key (:id item)}])]]))

(mf/defc sidebar-options
  [{:keys [local] :as props}]
  (let [filter-yours
        (mf/use-callback
         (mf/deps local)
         (st/emitf (dwcm/update-filters {:main :yours})))

        filter-all
        (mf/use-callback
         (mf/deps local)
         (st/emitf (dwcm/update-filters {:main :all})))

        toggle-resolved
        (mf/use-callback
         (mf/deps local)
         (st/emitf (dwcm/update-filters {:resolved (not (:filter-resolved local))})))]

  [:ul.dropdown.sidebar-options-dropdown
   [:li {:on-click filter-all} "All"]
   [:li {:on-click filter-yours} "Only yours"]
   [:hr]
   (if (:filter-resolved local)
     [:li {:on-click toggle-resolved} "Show resolved comments"]
     [:li {:on-click toggle-resolved} "Hide resolved comments"])]))

(mf/defc comments-sidebar
  []
  (let [threads-map (mf/deref threads-ref)
        profile     (mf/deref refs/profile)
        local       (mf/deref workspace-comments-ref)
        options?    (mf/use-state false)

        tgroups     (->> (vals threads-map)
                         (sort-by :modified-at)
                         (reverse)
                         (apply-filters local profile)
                         (group-threads-by-page))]

    [:div.workspace-comments.workspace-comments-sidebar
     [:div.sidebar-title
      [:div.label "Comments"]
      [:div.options {:on-click #(reset! options? true)}
       [:div.label (case (:filter local)
                     (nil :all) "All"
                     :yours     "Only yours")]
       [:div.icon i/arrow-down]]]

     [:& dropdown {:show @options?
                   :on-close #(reset! options? false)}
      [:& sidebar-options {:local local}]]

     (when (seq tgroups)
       [:div.threads
        [:& sidebar-item {:group (first tgroups)}]
        (for [tgroup (rest tgroups)]
          [:*
           [:hr]
           [:& sidebar-item {:group tgroup
                             :key (:page-id tgroup)}]])])]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- group-threads-by-page
  [threads]
  (letfn [(group-by-page [result thread]
            (let [current (first result)]
              (if (= (:page-id current) (:page-id thread))
                (cons (update current :items conj thread)
                      (rest result))
                (cons {:page-id (:page-id thread) :items [thread]}
                      result))))]
    (reverse
     (reduce group-by-page nil threads))))

(defn- apply-filters
  [local profile threads]
  (cond->> threads
    (true? (:filter-resolved local))
    (filter (fn [item]
              (or (not (:is-resolved item))
                  (= (:id item) (:open local)))))

    (= :yours (:filter local))
    (filter #(contains? (:participants %) (:id profile)))))

