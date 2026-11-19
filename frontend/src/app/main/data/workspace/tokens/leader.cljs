;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.leader
  "Which session sets and saves the token values of a page when several
  have the file open, so the work is done once.

  The editor with the lowest session id among the ones on the page goes
  first; the author of the last change of the tokens goes before everyone.
  The others wait, longer the further they are, and take over when the
  page is still out of date: presence can be wrong, since messages are
  dropped under load.")

(def settle-ms
  "Time without remote commits before checking the page, so the commits of
  the author arrive first."
  500)

(def ^:private take-over-ms 3000)
(def ^:private take-over-step-ms 1000)

(defn- editors
  "Session ids of the sessions in `presence` that can edit, on `page-id`
  when given."
  [presence page-id]
  (into []
        (keep (fn [[session-id {:keys [can-edit] :as session}]]
                (when (and can-edit
                           (or (nil? page-id) (= page-id (:page-id session))))
                  session-id)))
        presence))

(defn page-editor?
  "True when some session in `presence` that can edit has `page-id` open."
  [presence page-id]
  (boolean (seq (editors presence page-id))))

(defn author-first
  "`author-id` when the author of a change of the tokens goes first on
  `page-id`: it sets every page, or with `lazy?` only the one it is on."
  [presence page-id author-id lazy?]
  (when (and (some? author-id)
             (or (not lazy?) (= page-id (get-in presence [author-id :page-id]))))
    author-id))

(defn position
  "Position of `session-id` in the queue of the editors on `page-id`: 0 goes
  first. `author-id` is the session that made the last change of the
  tokens, ahead of everyone. With a nil `page-id` the queue covers the
  editors of the whole file."
  [presence session-id page-id author-id]
  (let [queue (->> (editors presence page-id)
                   (cons session-id)
                   (distinct)
                   (sort-by str))
        queue (if (some? author-id)
                (cons author-id (remove #(= % author-id) queue))
                queue)]
    (or (first (keep-indexed #(when (= %2 session-id) %1) queue)) 0)))

(defn file-leader?
  "True when `session-id` goes first among the editors of the whole file in
  `presence`."
  [presence session-id]
  (zero? (position presence session-id nil nil)))

(defn wait-ms
  "Time the session at `position` waits before setting the values of a page
  that is still out of date."
  [position]
  (if (zero? position)
    0
    (+ take-over-ms (* take-over-step-ms (dec position)))))
