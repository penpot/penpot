;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.branch-switcher
  "The branch row under the workspace left header: a chip naming the
  branch the open file is, its status next to it, and a popover to switch
  branch, create one or reach the full list. It only exists once the file
  has branches; before that the first branch comes from the main menu."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.pull-requests :as dwpr]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.pull-requests :as prui]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def has-branches
  "True when the open file is a branch or has branches; the branch row and
  its chip only show then."
  (l/derived dwb/has-branches? st/state))

(def ^:private branches
  (l/derived :workspace-branches st/state))

(def ^:private switcher-open
  (l/derived #(boolean (:workspace-branch-switcher %)) st/state))

(defn- open-pr-by-branch
  "The open pull requests indexed by the id of their branch."
  [prs]
  (->> (:data prs)
       (filter #(= "open" (:status %)))
       (map (juxt :file-branch-id identity))
       (into {})))

(defn- counts-error?
  [row]
  (or (:diff-error row)
      (nil? (:ahead row))))

;; --- Status: counts and the one pill that matters

(mf/defc branch-counts*
  {::mf/private true}
  [{:keys [row]}]
  (let [ahead  (or (:ahead row) 0)
        behind (or (:behind row) 0)]
    (if (counts-error? row)
      [:span {:class (stl/css :count-error)
              :title (tr "workspace.branches.counts.error")}
       [:> i/icon* {:icon-id i/triangle-alert :size "s"}]]
      (when (or (pos? ahead) (pos? behind))
        [:span {:class (stl/css :counts)}
         (when (pos? ahead)
           [:span {:class (stl/css :count)
                   :title (tr "workspace.branches.counts.ahead")}
            [:> i/icon* {:icon-id i/arrow-up :size "s"}]
            (dm/str ahead)])
         (when (pos? behind)
           [:span {:class (stl/css :count)
                   :title (tr "workspace.branches.counts.behind")}
            [:> i/icon* {:icon-id i/arrow-down :size "s"}]
            (dm/str behind)])]))))

(mf/defc branch-pill*
  "One pill per branch: conflicts win over the review state, because they
  block the merge whatever the reviewers say."
  {::mf/private true}
  [{:keys [row pr]}]
  (let [conflicts (or (:conflicts row) 0)]
    (cond
      (pos? conflicts)
      [:span {:class (stl/css :pill :pill-conflict)
              :title (tr "workspace.branches.banner.conflicts" (dm/str conflicts))}
       [:> i/icon* {:icon-id i/triangle-alert :size "s"}]
       (dm/str conflicts)]

      (some? pr)
      (let [[approvals total] (prui/current-approvals pr)
            approved?         (and (pos? total) (= approvals total))]
        [:span {:class (stl/css-case :pill true
                                     :pill-review (not approved?)
                                     :pill-approved approved?)
                :title (tr "workspace.pull-requests.info.approvals")}
         [:> i/icon* {:icon-id i/git-pull-request-arrow :size "s"}]
         (when (pos? total)
           (dm/str approvals "/" total))]))))

;; --- Popover

(mf/defc switcher-row*
  {::mf/private true}
  [{:keys [row pr current on-open]}]
  (let [main? (:is-main row)]
    [:li {:class (stl/css-case :row true :is-current current)}
     [:button {:class (stl/css :row-button)
               :data-branch (dm/str (:branch-file-id row))
               :aria-current (when current "true")
               :disabled current
               :on-click on-open}
      [:span {:class (stl/css :row-icon)}
       [:> i/icon* {:icon-id (if current i/tick i/git-branch) :size "s"}]]
      [:span {:class (stl/css :row-name)} (:name row)]
      [:span {:class (stl/css :row-meta)}
       (if main?
         [:span {:class (stl/css :row-default)} (tr "workspace.branches.main-subtitle")]
         [:*
          [:> branch-counts* {:row row}]
          [:> branch-pill* {:row row :pr pr}]])]]]))

(mf/defc switcher-popover*
  {::mf/private true}
  [{:keys [file current prs-by-branch on-close]}]
  (let [{:keys [data]} (mf/deref branches)
        custom-sc      (mf/deref refs/custom-shortcuts)

        filter*        (mf/use-state "")
        filter-v       (deref filter*)
        archived*      (mf/use-state false)
        show-archived? (deref archived*)

        matches?
        (fn [row]
          (or (str/blank? filter-v)
              (str/includes? (str/lower (or (:name row) "")) (str/lower filter-v))))

        ;; on a branch, main is a switch target too and goes first
        main-row
        (when-not (:is-main current)
          {:name (tr "workspace.branches.main")
           :is-main true
           :branch-file-id (:source-file-id current)})

        rows
        (mf/with-memo [data current]
          (remove #(= (:id %) (:id current)) data))

        others
        (cond->> (filter #(= "open" (:status %)) rows)
          main-row (cons main-row)
          :always  (filter matches?))

        archived
        (->> rows
             (remove #(= "open" (:status %)))
             (filter matches?))

        on-filter-change
        (mf/use-fn #(reset! filter* (dom/get-target-val %)))

        on-toggle-archived
        (mf/use-fn #(swap! archived* not))

        on-open
        (mf/use-fn
         (fn [event]
           (let [id (-> event dom/get-current-target (dom/get-data "branch") uuid/parse)]
             (st/emit! (dwb/open-branch id)))))

        on-create
        (mf/use-fn
         (mf/deps file on-close)
         (fn [_]
           (on-close)
           (modal/show! :create-branch {:file-name (:name file)})))

        on-show-all
        (mf/use-fn
         (mf/deps on-close)
         (fn [_]
           (on-close)
           (modal/show! :branches-list {})))]

    [:div {:class (stl/css :popover)
           :role "dialog"
           :aria-label (tr "workspace.branches.menu")}
     [:> input* {:variant "dense"
                 :icon i/search
                 :auto-focus true
                 :placeholder (tr "workspace.branches.search.placeholder")
                 :value filter-v
                 :on-change on-filter-change}]

     [:div {:class (stl/css :section)} (tr "workspace.branches.section.current")]
     [:ul {:class (stl/css :rows)}
      [:> switcher-row* {:row current
                         :pr (get prs-by-branch (:id current))
                         :current true}]]

     (when (seq others)
       [:*
        [:div {:class (stl/css :section)}
         [:span (tr "workspace.branches.section.others")]
         [:span (dm/str (count others))]]
        [:ul {:class (stl/css :rows)}
         (for [row others]
           [:> switcher-row* {:key (dm/str (:branch-file-id row))
                              :row row
                              :pr (get prs-by-branch (:id row))
                              :on-open on-open}])]])

     (when (seq archived)
       [:*
        [:button {:class (stl/css :action)
                  :aria-expanded show-archived?
                  :on-click on-toggle-archived}
         [:> i/icon* {:icon-id (if show-archived? i/arrow-down i/arrow-right) :size "s"}]
         [:span {:class (stl/css :action-label)} (tr "workspace.branches.archived")]
         [:span {:class (stl/css :action-aside)} (dm/str (count archived))]]
        (when show-archived?
          [:ul {:class (stl/css :rows)}
           (for [row archived]
             [:> switcher-row* {:key (dm/str (:branch-file-id row))
                                :row row
                                :on-open on-open}])])])

     [:hr {:class (stl/css :divider)}]

     [:button {:class (stl/css :action) :on-click on-create}
      [:> i/icon* {:icon-id i/git-branch-plus :size "s"}]
      [:span {:class (stl/css :action-label)} (tr "workspace.branches.switcher.new")]]

     [:button {:class (stl/css :action) :on-click on-show-all}
      [:> i/icon* {:icon-id i/menu :size "s"}]
      [:span {:class (stl/css :action-label)} (tr "workspace.branches.switcher.all")]
      [:span {:class (stl/css :action-aside)}
       (for [key (scd/split-sc (sc/get-effective-tooltip :open-branches custom-sc))]
         [:span {:key key :class (stl/css :shortcut-key)} key])]]]))

;; --- Branch row under the header

(mf/defc branch-bar*
  [{:keys [file]}]
  (let [file-id   (:id file)
        visible?  (mf/deref has-branches)
        open?     (mf/deref switcher-open)
        ctx       (mf/deref refs/branch-context)
        profile   (mf/deref refs/profile)
        prs       (mf/deref refs/pull-requests)

        prs-by-branch
        (mf/with-memo [prs]
          (when (dwpr/enabled?)
            (open-pr-by-branch prs)))

        ;; what the open file is: its branch row, or main
        current
        (or ctx
            {:name (tr "workspace.branches.main")
             :is-main true
             :created-by (:id profile)
             :branch-file-id file-id})

        merged?   (contains? #{"merged" "archived"} (:status ctx))

        ;; clicks inside the row (chip and popover) must not close it
        bar-ref   (mf/use-ref nil)

        on-toggle
        (mf/use-fn
         (fn [event]
           ;; the dropdown closes itself on any outside click: the click
           ;; that opens it must not reach it
           (dom/stop-propagation event)
           (st/emit! (dwb/toggle-branch-switcher))))

        on-close
        (mf/use-fn #(st/emit! (dwb/toggle-branch-switcher false)))]

    ;; the list decides whether the row shows at all, so it loads with the
    ;; file (the bottom banner loads the branch context)
    (mf/with-effect [file-id]
      (when (contains? cf/flags :branching)
        (st/emit! (dwb/init-branches-state)
                  (dwb/toggle-branch-switcher false))))

    ;; the row makes the header taller; panels that size themselves against
    ;; the viewport read this to keep their bottom edge in place
    (mf/with-effect [visible?]
      (let [root (dom/get-root)]
        (if visible?
          (dom/set-css-property! root "--branch-bar-block-size" "var(--branch-bar-size)")
          (dom/unset-css-property! root "--branch-bar-block-size"))
        (fn [] (dom/unset-css-property! root "--branch-bar-block-size"))))

    (when ^boolean visible?
      [:div {:class (stl/css :branch-bar) :ref bar-ref}
       [:button {:class (stl/css-case :chip true :is-open open?)
                 :aria-haspopup "dialog"
                 :aria-expanded open?
                 :title (if (:is-main current)
                          (tr "workspace.branches.switcher.tooltip")
                          (tr "workspace.branches.header-badge-tooltip" (:name current)))
                 :on-click on-toggle}
        [:> i/icon* {:icon-id i/git-branch :size "s" :class (stl/css :chip-icon)}]
        [:span {:class (stl/css :chip-name)} (:name current)]
        [:> i/icon* {:icon-id i/arrow-down :size "s" :class (stl/css :chip-arrow)}]]

       (when (and (some? ctx) (not merged?))
         [:div {:class (stl/css :status)}
          [:> branch-counts* {:row ctx}]
          [:> branch-pill* {:row ctx :pr (get prs-by-branch (:id ctx))}]])

       [:& dropdown {:show open? :on-close on-close :container bar-ref}
        [:> switcher-popover* {:file file
                               :current current
                               :prs-by-branch prs-by-branch
                               :on-close on-close}]]])))
