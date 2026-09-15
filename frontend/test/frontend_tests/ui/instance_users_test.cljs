;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.instance-users-test
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom/client" :as client]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.ui.settings.instance-users :as users]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]
   [promesa.core :as p]))

(t/deftest administrator-can-load-users-and-edit-memberships
  (t/async done
    (let [browser (JSDOM. "<!doctype html><html><body><div id='root'></div></body></html>"
                          #js {:url "http://localhost/"})
          window  (.-window browser)
          doc     (.-document window)
          before  {:window (.-window js/globalThis) :document (.-document js/globalThis)
                   :globals-window globals/window :globals-document globals/document
                   :tr i18n/tr :cmd rp/cmd! :act (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)}
          id      (uuid/random)
          team-id (uuid/random)
          new-id  (uuid/random)
          model   (atom {:id id :fullname "Example User" :email "user@example.com"
                         :teams [{:id team-id :name "Design" :can-edit false}]
                         :projects []})
          calls   (atom [])
          root    (client/createRoot (.getElementById doc "root"))
          button  (fn [label] (some #(when (= label (.-textContent %)) %) (array-seq (.querySelectorAll doc "button"))))
          click   (fn [element] (.dispatchEvent element (js/Reflect.construct (.-MouseEvent window) #js ["click" #js {:bubbles true}])))
          change  (fn [element value]
                    (set! (.-value element) value)
                    (.dispatchEvent element (js/Reflect.construct (.-Event window) #js ["change" #js {:bubbles true}])))]
      (set! (.-window js/globalThis) window)
      (set! (.-document js/globalThis) doc)
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (set! globals/window window)
      (set! globals/document doc)
      (set! i18n/tr (fn ([key] key) ([key & _] key)))
      (set! rp/cmd!
            (fn rpc-stub
              ([command] (rpc-stub command {}))
              ([command params]
               (swap! calls conj [command params])
               (case command
                 :get-instance-users (rx/of {:users [@model] :total 1})
                 :get-instance-membership-targets (rx/of [{:id new-id :name "Research"}])
                 :set-instance-user-membership
                 (do
                   (swap! model assoc :teams
                          (case (:role params)
                            :none []
                            [{:id (:target-id params)
                              :name (if (= new-id (:target-id params)) "Research" "Design")
                              :can-edit true}]))
                   (rx/of (select-keys @model [:teams :projects])))
                 (rx/throw (js/Error. (str "Unexpected RPC: " command)))))))
      (-> (p/let [_ (react/act #(.render root (react/createElement users/instance-users-page* #js {})))
                  _ (t/is (str/includes? (.-textContent (.-body doc)) "user@example.com"))
                  _ (react/act #(click (button "admin.users.manage")))
                  _ (react/act #(change (.querySelector doc "select[aria-label]") "editor"))
                  _ (t/is (some #(= [:set-instance-user-membership
                                     {:member-id id :kind :team :target-id team-id :role :editor}] %) @calls))
                  _ (react/act #(click (button "labels.remove")))
                  _ (t/is (empty? (:teams @model)))
                  _ (react/act #(change (.querySelector doc (str "select:has(option[value='" new-id "'])")) (str new-id)))
                  _ (react/act #(.dispatchEvent (.closest (button "admin.users.add") "form")
                                                (js/Reflect.construct (.-Event window) #js ["submit" #js {:bubbles true :cancelable true}])))]
            (t/is (= new-id (:id (first (:teams @model)))))
            (t/is (str/includes? (.-textContent (.-body doc)) "Research")))
          (p/catch (fn [cause] (t/is false (str cause))))
          (p/finally
            (fn []
              (react/act #(.unmount root))
              (set! rp/cmd! (:cmd before))
              (set! i18n/tr (:tr before))
              (set! globals/window (:globals-window before))
              (set! globals/document (:globals-document before))
              (set! (.-window js/globalThis) (:window before))
              (set! (.-document js/globalThis) (:document before))
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) (:act before))
              (.close window)
              (done)))))))
