;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.settings.instance-users
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn membership-role
  [{:keys [is-owner is-admin can-edit]}]
  (cond is-owner :owner is-admin :admin can-edit :editor :else :viewer))

(mf/defc membership-list*
  [{:keys [items kind busy on-change]}]
  [:ul {:class (stl/css :memberships)}
   (for [item items]
     [:li {:key (:id item)}
      [:span (:name item)]
      [:select {:aria-label (str (tr "admin.users.role") " — " (:name item))
                :value (name (membership-role item))
                :disabled (or busy (:is-owner item) (and (= kind :team) (:is-default item)))
                :on-change #(on-change kind (:id item) (keyword (dom/get-target-val %)))}
       (when (:is-owner item) [:option {:value "owner"} (tr "labels.owner")])
       (for [role [:viewer :editor :admin]]
         [:option {:key role :value (name role)} (tr (str "labels." (name role)))])]
      (when-not (or (:is-owner item) (and (= kind :team) (:is-default item)))
        [:button {:class (stl/css :button) :type "button" :disabled busy
                  :on-click #(on-change kind (:id item) :none)}
         (tr "labels.remove")])])])

(mf/defc add-membership*
  [{:keys [busy on-change]}]
  (let [kind    (mf/use-state :team)
        search  (mf/use-state "")
        offset  (mf/use-state 0)
        targets (mf/use-state [])
        target  (mf/use-state "")
        role    (mf/use-state :editor)
        error   (mf/use-state false)]
    (mf/with-effect [@kind @search @offset]
      (reset! target "")
      (reset! targets [])
      (reset! error false)
      (let [sub (->> (rp/cmd! :get-instance-membership-targets
                              {:kind @kind :search @search :offset @offset})
                     (rx/subs! #(reset! targets %) #(reset! error true)))]
        #(rx/dispose! sub)))
    [:form {:class (stl/css :add-membership)
            :on-submit (fn [event]
                         (dom/prevent-default event)
                         (when-let [id (uuid/parse* @target)]
                           (on-change @kind id @role)))}
     [:label (tr "admin.users.kind")
      [:select {:value (name @kind) :on-change #(do (reset! offset 0)
                                                    (reset! kind (keyword (dom/get-target-val %))))}
       [:option {:value "team"} (tr "admin.users.teams")]
       [:option {:value "project"} (tr "admin.users.projects")]]]
     [:label (tr "admin.users.find-target")
      [:input {:type "search" :value @search
               :on-change #(do (reset! offset 0) (reset! search (dom/get-target-val %)))}]]
     [:label (tr "admin.users.target")
      [:select {:required true :value @target :on-change #(reset! target (dom/get-target-val %))}
       [:option {:value ""} (tr "admin.users.select-target")]
       (for [item @targets]
         [:option {:key (:id item) :value (str (:id item))}
          (str (when (:team-name item) (str (:team-name item) " / ")) (:name item))])]]
     [:div {:class (stl/css :pagination)}
      [:button {:class (stl/css :button) :type "button" :disabled (zero? @offset) :on-click #(swap! offset - 50)} (tr "admin.users.previous")]
      [:button {:class (stl/css :button) :type "button" :disabled (< (count @targets) 50) :on-click #(swap! offset + 50)} (tr "admin.users.next")]]
     [:label (tr "admin.users.role")
      [:select {:value (name @role) :on-change #(reset! role (keyword (dom/get-target-val %)))}
       (for [value [:viewer :editor :admin]]
         [:option {:key value :value (name value)} (tr (str "labels." (name value)))])]]
     (when @error [:p {:role "alert"} (tr "admin.users.load-error")])
     [:button {:class (stl/css :button) :type "submit" :disabled (or busy (empty? @target))} (tr "admin.users.add")]]))

(mf/defc instance-users-page*
  []
  (let [search   (mf/use-state "")
        query    (mf/use-state "")
        offset   (mf/use-state 0)
        revision (mf/use-state 0)
        result   (mf/use-state {:users [] :total 0})
        selected (mf/use-state nil)
        loading  (mf/use-state true)
        busy     (mf/use-state false)
        error    (mf/use-state nil)
        account  (some #(when (= @selected (:id %)) %) (:users @result))
        change   (mf/use-fn
                  (mf/deps @selected)
                  (fn [kind target-id role]
                    (reset! busy true)
                    (reset! error nil)
                    (->> (rp/cmd! :set-instance-user-membership
                                  {:member-id @selected :kind kind :target-id target-id :role role})
                         (rx/subs! (fn [_] (reset! busy false) (swap! revision inc))
                                   (fn [cause]
                                     (reset! busy false)
                                     (reset! error (if (= :team-membership-required (:code (ex-data cause)))
                                                     (tr "admin.users.team-required")
                                                     (tr "admin.users.save-error"))))))))]
    (mf/with-effect [@query @offset @revision]
      (reset! loading true)
      (reset! error nil)
      (let [sub (->> (rp/cmd! :get-instance-users {:search @query :offset @offset :limit 50})
                     (rx/subs! (fn [data] (reset! result data) (reset! loading false))
                               (fn [_] (reset! loading false) (reset! error (tr "admin.users.load-error")))))]
        #(rx/dispose! sub)))
    [:section {:class (stl/css :users) :aria-labelledby "instance-users-title"}
     [:h2 {:id "instance-users-title"} (tr "admin.users.title")]
     [:form {:class (stl/css :search)
             :on-submit #(do (dom/prevent-default %) (reset! offset 0) (reset! query @search))}
      [:div {:class (stl/css :search-field)}
       [:span {:class (stl/css :search-icon) :aria-hidden true} i/search]
       [:input {:id "instance-user-search" :type "search" :value @search
                :aria-label (tr "admin.users.search")
                :placeholder (tr "admin.users.search")
                :on-change #(reset! search (dom/get-target-val %))}]]
      [:button {:class (stl/css :button :search-button) :type "submit" :disabled (or @busy @loading)}
       (tr "admin.users.search-action")]]
     (when @error
       [:div {:class (stl/css :error-notice) :role "alert"}
        [:p @error]
        [:button {:class (stl/css :button) :type "button" :disabled (or @busy @loading)
                  :on-click #(swap! revision inc)} (tr "labels.retry")]])
     (if @loading
       [:p {:role "status"} (tr "labels.loading")]
       [:table
        [:thead [:tr [:th (tr "admin.users.name")] [:th (tr "admin.users.email")]
                 [:th (tr "admin.users.teams")] [:th (tr "admin.users.projects")]
                 [:th (tr "admin.users.manage")]]]
        [:tbody
         (for [user (:users @result)]
           [:tr {:key (:id user)}
            [:td (:fullname user)] [:td (:email user)]
            [:td (for [team (:teams user)] [:div {:key (:id team)} (:name team)])]
            [:td (for [project (:projects user)] [:div {:key (:id project)} (:name project)])]
            [:td [:button {:class (stl/css :button) :type "button" :disabled @busy :on-click #(reset! selected (:id user))}
                  (tr "admin.users.manage")]]])]])
     (when (and (not @error) (not @loading) (pos? (:total @result)))
       [:div {:class (stl/css :pagination)}
        [:button {:class (stl/css :button) :type "button" :disabled (or @busy @loading (zero? @offset))
                  :on-click #(do (reset! selected nil) (swap! offset - 50))} (tr "admin.users.previous")]
        [:span (str (min (+ @offset 50) (:total @result)) " / " (:total @result))]
        [:button {:class (stl/css :button) :type "button" :disabled (or @busy @loading (>= (+ @offset 50) (:total @result)))
                  :on-click #(do (reset! selected nil) (swap! offset + 50))} (tr "admin.users.next")]])
     (when account
       [:section {:class (stl/css :editor) :aria-label (:email account)}
        [:h3 (:email account)]
        [:p (tr "admin.users.inheritance")]
        [:h4 (tr "admin.users.teams")]
        [:> membership-list* {:items (:teams account) :kind :team :busy @busy :on-change change}]
        [:h4 (tr "admin.users.projects")]
        [:> membership-list* {:items (:projects account) :kind :project :busy @busy :on-change change}]
        [:> add-membership* {:busy @busy :on-change change}]])]))
