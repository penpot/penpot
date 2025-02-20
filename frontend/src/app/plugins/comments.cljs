;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.comments
  (:require
   [app.common.geom.point :as gpt]
   [app.common.spec :as us]
   [app.main.data.comments :as dc]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.comments :as dwc]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.register :as r]
   [app.plugins.shape :as shape]
   [app.plugins.user :as user]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

(defn comment-proxy? [p]
  (obj/type-of? p "CommentProxy"))

(defn comment-proxy
  [plugin-id file-id page-id thread-id data]
  (let [data* (atom data)]
    (obj/reify {:name "CommentProxy"}
      ;; Private properties
      :$plugin {:enumerable false :get (fn [] plugin-id)}
      :$file {:enumerable false :get (fn [] file-id)}
      :$page {:enumerable false :get (fn [] page-id)}
      :$thread {:enumerable false :get (fn [] thread-id)}
      :$id {:enumerable false :get (fn [] (:id data))}

      ;; Public properties

      ;; FIXME: inconsistent with comment-thread: owner
      :user
      {:get #(->> (dc/get-owner data)
                  (user/user-proxy plugin-id))}

      :owner
      {:get #(->> (dc/get-owner data)
                  (user/user-proxy plugin-id))}

      :date
      {:get
       (fn [] (:created-at data))}

      :content
      {:get
       (fn [] (:content @data*))

       :set
       (fn [content]
         (let [profile (:profile @st/state)]
           (cond
             (or (not (string? content)) (empty? content))
             (u/display-not-valid :content "Not valid")

             (not= (:id profile) (:owner-id data))
             (u/display-not-valid :content "Cannot change content from another user's comments")

             (not (r/check-permission plugin-id "comment:write"))
             (u/display-not-valid :content "Plugin doesn't have 'comment:write' permission")

             :else
             (->> (rp/cmd! :update-comment {:id (:id data) :content content})
                  (rx/tap #(st/emit! (dc/retrieve-comment-threads file-id)))
                  (rx/subs! #(swap! data* assoc :content content))))))}

      ;; Public methods
      :remove
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "comment:write"))
             (do
               (u/display-not-valid :remove "Plugin doesn't have 'comment:write' permission")
               (reject "Plugin doesn't have 'comment:write' permission"))

             :else
             (->> (rp/cmd! :delete-comment {:id (:id data)})
                  (rx/tap #(st/emit! (dc/retrieve-comment-threads file-id)))
                  (rx/subs! #(resolve) reject)))))))))

(defn comment-thread-proxy? [p]
  (obj/type-of? p "CommentThreadProxy"))

(defn comment-thread-proxy
  [plugin-id file-id page-id data]
  (let [data* (atom data)]
    (obj/reify {:name "CommentThreadProxy"}
      :$plugin {:enumerable false :get (fn [] plugin-id)}
      :$file {:enumerable false :get (fn [] file-id)}
      :$page {:enumerable false :get (fn [] page-id)}
      :$id {:enumerable false :get (fn [] (:id data))}

      :page {:enumerable false :get #(u/locate-page file-id page-id)}
      :seqNumber {:get #(:seqn data)}
      :board {:get #(shape/shape-proxy plugin-id file-id page-id (:frame-id data))}

      :owner
      {:get #(->> (dc/get-owner data)
                  (user/user-proxy plugin-id))}

      :position
      {:get
       (fn []
         (format/format-point (:position @data*)))

       :set
       (fn [position]
         (let [position (parser/parse-point position)]
           (cond
             (or (not (us/safe-number? (:x position))) (not (us/safe-number? (:y position))))
             (u/display-not-valid :position "Not valid point")

             (not (r/check-permission plugin-id "comment:write"))
             (u/display-not-valid :position "Plugin doesn't have 'comment:write' permission")

             :else
             (do (st/emit! (dwc/update-comment-thread-position @data* [(:x position) (:y position)]))
                 (swap! data* assoc :position (gpt/point position))))))}

      :resolved
      {:get
       (fn [] (:is-resolved @data*))

       :set
       (fn [is-resolved]
         (cond
           (not (boolean? is-resolved))
           (u/display-not-valid :resolved "Not a boolean type")

           (not (r/check-permission plugin-id "comment:write"))
           (u/display-not-valid :resolved "Plugin doesn't have 'comment:write' permission")

           :else
           (do (st/emit! (dc/update-comment-thread (assoc @data* :is-resolved is-resolved)))
               (swap! data* assoc :is-resolved is-resolved))))}

      :findComments
      (fn []
        (js/Promise.
         (fn [resolve reject]
           (cond
             (not (r/check-permission plugin-id "comment:read"))
             (do
               (u/display-not-valid :findComments "Plugin doesn't have 'comment:read' permission")
               (reject "Plugin doesn't have 'comment:read' permission"))

             :else
             (->> (rp/cmd! :get-comments {:thread-id (:id data)})
                  (rx/subs!
                   (fn [comments]
                     (resolve
                      (format/format-array
                       #(comment-proxy plugin-id file-id page-id (:id data) %) comments)))
                   reject))))))

      :reply
      (fn [content]
        (cond
          (not (r/check-permission plugin-id "comment:write"))
          (u/display-not-valid :reply "Plugin doesn't have 'comment:write' permission")

          (or (not (string? content)) (empty? content))
          (u/display-not-valid :reply "Not valid")

          :else
          (js/Promise.
           (fn [resolve reject]
             (->> (rp/cmd! :create-comment {:thread-id (:id data) :content content})
                  (rx/subs! #(resolve (comment-proxy plugin-id file-id page-id (:id data) %)) reject))))))

      :remove
      (fn []
        (let [profile (:profile @st/state)
              owner   (dsh/lookup-profile @st/state (:owner-id data))]
          (cond
            (not (r/check-permission plugin-id "comment:write"))
            (u/display-not-valid :remove "Plugin doesn't have 'comment:write' permission")

            (not= (:id profile) owner)
            (u/display-not-valid :remove "Cannot change content from another user's comments")

            :else
            (js/Promise.
             (fn [resolve]
               (st/emit! (dc/delete-comment-thread-on-workspace {:id (:id data)} #(resolve)))))))))))
