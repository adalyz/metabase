(ns metabase.models.public-card
  (:require [metabase.models.interface :as i]
            [metabase.util :as u]))

(i/defentity PublicCard :public_card)

(defn- pre-insert [public-card]
  (merge {:uuid (str (java.util.UUID/randomUUID))}
         public-card))

(u/strict-extend (class PublicCard)
  i/IEntity
  (merge i/IEntityDefaults
         {:pre-insert pre-insert}))
