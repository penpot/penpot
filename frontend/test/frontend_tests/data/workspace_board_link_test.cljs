;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-board-link-test
  "The link `copy-link-to-clipboard` puts on the clipboard: one that
  deep-links to the boards of the selection, or the current location
  when the selection has none."
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.clipboard :as dwcl]
   [app.main.data.workspace.selection :as dws]
   [app.main.router :as rt]
   [app.util.clipboard :as clipboard]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(def ^:private current-href "https://penpot.test/#/workspace")
(def ^:private board-link "https://penpot.test/#/workspace?board-id")

(defn- setup-file
  "Two boards with a rectangle in each, and a rectangle on the root."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :board-1)
      (ctho/add-frame :board-2)
      (cths/add-sample-shape :rect-1 :parent-label :board-1)
      (cths/add-sample-shape :rect-2 :parent-label :board-2)
      (cths/add-sample-shape :rect-root)))

(defn- copy-link
  "Runs `copy-link-to-clipboard` with the shapes labelled `selected`
  selected. Returns the link that reached the clipboard and the route
  params `resolve-uri` was asked for, which is nil when it was not."
  [selected]
  (let [store    (ths/setup-store (setup-file))
        state    (assoc-in @store [:workspace-local :selected]
                           (into #{} (map cthi/id) selected))
        resolved (volatile! nil)
        copied   (volatile! nil)]
    (with-redefs [rt/lookup-name       (constantly :workspace)
                  rt/get-params        (constantly {:file-id "file"})
                  rt/get-current-href  (constantly current-href)
                  rt/resolve-uri       (fn [_router _name params]
                                         (vreset! resolved params)
                                         board-link)
                  clipboard/to-clipboard (fn [link] (vreset! copied link))]
      (ptk/watch (dwcl/copy-link-to-clipboard) state nil))
    {:link @copied
     :params @resolved}))

(defn- linked-boards
  [result]
  (set (get-in result [:params :board-id])))

(t/deftest a-selected-board-links-to-that-board
  (let [result (copy-link [:board-1])]
    (t/is (= board-link (:link result)))
    (t/is (= #{(str (cthi/id :board-1))} (linked-boards result)))))

(t/deftest a-shape-in-a-board-links-to-its-board
  (let [result (copy-link [:rect-1])]
    (t/is (= board-link (:link result)))
    (t/is (= #{(str (cthi/id :board-1))} (linked-boards result)))))

(t/deftest shapes-in-two-boards-link-to-both-boards
  (let [result (copy-link [:rect-1 :rect-2])]
    (t/is (= #{(str (cthi/id :board-1)) (str (cthi/id :board-2))}
             (linked-boards result)))))

(t/deftest a-board-and-its-child-give-one-board-id
  (let [result (copy-link [:board-1 :rect-1])]
    (t/is (= [(str (cthi/id :board-1))]
             (get-in result [:params :board-id])))))

(t/deftest the-route-params-are-kept-next-to-the-board-ids
  (t/is (= "file" (get-in (copy-link [:board-1]) [:params :file-id]))))

(t/deftest nothing-selected-copies-the-current-location
  (let [result (copy-link [])]
    (t/is (= current-href (:link result)))
    (t/is (nil? (:params result)))))

(t/deftest a-shape-on-the-root-copies-the-current-location
  ;; Its frame is the root, which is not a board to link to
  (let [result (copy-link [:rect-root])]
    (t/is (= current-href (:link result)))
    (t/is (nil? (:params result)))))

(t/deftest a-root-shape-beside-a-board-copies-the-current-location
  ;; The same rule as the URL of the selection: a shape on the root
  ;; means there is no board to point at, even with a board selected
  (let [result (copy-link [:board-1 :rect-root])]
    (t/is (= current-href (:link result)))
    (t/is (nil? (:params result)))))

(defn- selected-board-ids
  "`selected-board-ids` over the shapes labelled `selected`. Read the ids
  of the labels after calling it, which is what creates them."
  [selected]
  (let [store   (ths/setup-store (setup-file))
        objects (dsh/lookup-page-objects @store)]
    (dws/selected-board-ids objects (into (d/ordered-set) (map cthi/id) selected))))

(t/deftest selected-board-ids-of-a-board-is-the-board
  (let [ids (selected-board-ids [:board-1])]
    (t/is (= [(cthi/id :board-1)] ids))))

(t/deftest selected-board-ids-of-a-shape-is-its-board
  (let [ids (selected-board-ids [:rect-2])]
    (t/is (= [(cthi/id :board-2)] ids))))

(t/deftest selected-board-ids-lists-a-board-once
  (let [ids (selected-board-ids [:rect-1 :board-1])]
    (t/is (= [(cthi/id :board-1)] ids))))

(t/deftest selected-board-ids-keeps-the-selection-order
  (let [ids (selected-board-ids [:rect-2 :rect-1])]
    (t/is (= [(cthi/id :board-2) (cthi/id :board-1)] ids))))

(t/deftest selected-board-ids-is-nil-for-a-shape-on-the-root
  (t/is (nil? (selected-board-ids [:rect-root]))))

(t/deftest selected-board-ids-is-nil-when-a-root-shape-is-among-boards
  (t/is (nil? (selected-board-ids [:board-1 :rect-root]))))

(t/deftest selected-board-ids-is-nil-for-an-empty-selection
  (t/is (nil? (selected-board-ids []))))
