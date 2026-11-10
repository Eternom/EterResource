# EterResource

Les **mondes ressources** côté **Paper**. Document développeur, à tenir à jour avec le code.
Les serveurs eux-mêmes (création, monde neuf, remplacement) sont gérés côté proxy par **EterVelocityResource**.

Un seul jar, deux rôles selon le serveur :

- **Accès** (lobbys, survies… partout) : `/resource` pour acheter du temps, utiliser une clé, choisir un monde et
  partir ; minutes gratuites du jour ; sac de retour ; récapitulatif de la dernière sortie.
- **Monde ressource** (server-name qui commence par `world-server-prefix`, `ressource` par défaut, comme le
  `name-prefix` d'EterVelocityResource) : thème, camp de base, temps, fermeture, lieux, événements, règles, bonus.

## Prérequis

- **EterLib 1.25.0+** (`depend`) : base, langues, menus, Dialogs, annuaire des joueurs, envoi vers un serveur, actions,
  activité (AFK), sidebar, pont des quêtes (QuestBridge), classements.
- **EterEconomy 2.2.2+** (`depend`, `EconomyApi`) : achat de temps, marchand du camp.
- **EterJob** (`softdepend`, `JobApi`) : métier, rang et avantages du rang ; le relais de la guilde (`eterjob:relay`).
- Sur un monde ressource, `required-plugins` (EterJob, EterQuest par défaut) : sans eux, le monde n'est jamais proposé.

## Accès (`module/access`)

- Table `eterresource_access` : temps (ms), clés, jour des minutes gratuites, session ouverte. Chaque changement est UNE
  requête qui vérifie ses conditions : jamais de temps en trop (double clic, deux serveurs).
- **Offres** (`access.offers`, 3 au plus) : plus long, moins cher à la minute ; **heure creuse** (`access.off-peak`) :
  peu de joueurs sur les mondes ouverts, le temps est moins cher. Plafond `max-minutes`.
- **Minutes du jour** (`daily-free-minutes`), ou d'un **abonnement** : permission `eterresource.daily.<minutes>`.
- **Cadeau** : `/resource gift <joueur> <minutes>` (prévenu partout par le bus). **Rejoindre un ami** :
  `/resource join <joueur>`. **Historique** (`eterresource_history`, 90 jours) dans le menu.
- **Remboursement** : une session restée ouverte sur un monde qui a planté est rendue (+ `crash-refund-minutes`).
- **Récapitulatif** (`eterresource_recaps`) : à la sortie, blocs, minerais, bûches, monstres, temps ; montré à
  l'arrivée sur le serveur suivant.
- Menu `/resource` : joueur (temps, clés, minutes du jour, avantages du métier), offres, clé, **mondes ouverts**
  (thème, joueurs, fermeture, TPS ; clic pour y aller), historique, partir, offrir.
- Actions EterLib : `eterresource:menu` (un PNJ ou un portail du lobby), `eterresource:go`,
  `eterresource:addtime <minutes>` (récompense de quête), `eterresource:bag`.

## Monde ressource (`module/world`, `module/theme`)

- **Thèmes** (`themes`, rotation par jour `rotation`, `force-theme`) : biomes où s'installe le camp (le centre du monde,
  `locateNearestBiome`), bordure, durée de vie (`lifetime-hours` → `close_at`), minerais en plus, bonus de métier,
  événements plus fréquents. Forêt, montagnes, désert, océan, toundra, jungle, et le monde « filon » (riche, court).
- `WorldSetup` : inventaire gardé, pas de PvP, **pas de sauvegarde automatique** (monde jetable), bordures,
  prégénération ; prêt seulement après, plugins requis présents. Signe de vie (`eterresource_`/eterresource givekey|givetime|communitykey|info|testmode|worlds|event [info|tp|stop|<type>]|places [tp <n°>]``, 10 s) : thème,
  joueurs, TPS, ouverture, fermeture, mode test (`/eterresource `/eterresource givekey|givetime|communitykey|info|testmode|worlds|event [info|tp|stop|<type>]|places [tp <n°>]` on`). Journal des mondes
  (`eterresource_world_log`, `/eterresource `/eterresource givekey|givetime|communitykey|info|testmode|worlds|event [info|tp|stop|<type>]|places [tp <n°>]``). Un nouveau monde prêt est annoncé sur tout le réseau.
- **Camp de base** (`Camp`) : plateforme sûre au centre (même sur l'eau), spawn, postes (`camp.stations` : bloc +
  action EterLib : sac de retour, relais de la guilde, marchand, carnet d'expédition), protégé ; **temps en pause** au
  camp.
- `Sessions` : temps qui s'écoule chaque seconde, sauf au camp et pendant l'**AFK** ; × `time.dimension-multiplier`
  dans le Nether et l'End ; rappels, compte à rebours, **sidebar** (thème, temps, fermeture, événement, récolte),
  **arrivée animée** ; envoi en base toutes les 30 s et au départ.
- `Closing` : annonces à 1 h, 30, 10, 5, 1 min, barre de boss la dernière demi-heure, **tempête finale** (la bordure se
  resserre vers le camp) ; à l'heure, chacun part sur un autre monde ouvert (sinon au lobby). EterVelocityResource vide
  et remplace le serveur à `close_at`.

## Contenu (`module/content`)

- **Sac de retour** (`ReturnBag`, `eterresource_bags`) : petit coffre personnel commun au réseau, objets complets ;
  `bag.rows`, permission `eterresource.bag.<lignes>`.
- **Marchand du camp** (`CampShop`, `shop.items`) et **marchand ambulant** (`merchant`, échanges du réseau).
- **Lieux** (`Places`, `places`) : camp de mineurs, tour de guet, ruines, donjon souterrain, puits de lave (coffres et
  butin), filons riches, bosquets ; découverte en s'approchant ; **carnet d'expédition** (livre : direction, distance,
  coordonnées des lieux découverts).
- **Minerais en plus** (`OrePopulator`, `ore-multiplier` du thème).
- **Événements** (`WorldEvents`, `events`) : ruée vers l'or, forêt dorée, météorite, trésor caché (chaud / froid), défi
  chronométré, **ouverture de l'End** (le portail ne mène à l'End que pendant elle, places limitées) ;
  `/eterresource event <type>`. Pas de boss (plus tard, avec EterMob).
- **Nuit** plus dangereuse (`night`), **ressource du jour**, **bonus de groupe** (`bonus.group-chance`), **bonus de
  métier du thème**.
- **Règles** (`Guard`, `guard`) : coffres au propriétaire, créatures et hoppers limités par chunk, pas d'œuf sur un
  spawner, alerte de cadence au staff.
- **Classement** EterLib `resource_week` (blocs récoltés de la semaine, `eterresource_stats`).
- **Quêtes** (EterQuest, par QuestBridge) : `eterresource:arrive {theme}`, `eterresource:discover {place, detail}`,
  `eterresource:event {event, theme}`, `eterresource:meteor {block}`, `eterresource:treasure`, `eterresource:challenge`.
  Quêtes de métier du monde ressource : dans les fichiers d'EterJob (`where: {servers: [ressource]}`).

## Bonus de métier (`module/bonus`)

Avantages relus à l'arrivée puis chaque minute dans EterJob (`JobApi#perks`) : effets, abattage, petits filons, fonte
directe, minerais, récoltes, butin, XP et têtes, trésors et pêche. Les effets sont retirés au départ en `LOWEST`, AVANT
qu'EterSync ne les enregistre. Les blocs posés par un joueur (EterLib `PlacedBlocks`) ne donnent aucun bonus.

## Permissions

| Permission | Par défaut | Rôle |
|---|---|---|
| `eterresource.use` | tous | `/resource` |
| `eterresource.daily.<minutes>` | - | Minutes gratuites par jour (abonnement) |
| `eterresource.bag.<lignes>` | - | Sac de retour plus grand (3 lignes au plus) |
| `eterresource.bypass.time` | op | Monde ressource sans temps |
| `eterresource.admin` | op | Tout : `/eterresource givekey|givetime|communitykey|info|testmode|worlds|event [info|tp|stop|<type>]|places [tp <n°>]`\|givetime\|communitykey\|info\|testmode\|worlds\|event` |

## Technique

- Pas de config à garder sur un monde ressource : les valeurs du jar suffisent (le modèle n'a pas de dossier
  `plugins/EterResource`). Si on change `world-server-prefix`, changer aussi le `name-prefix` d'EterVelocityResource.
- API : `fr.eternom.eterResource.api.ResourceApi` (`access`, `addTime`, `giveKeys`, `isResourceServer`, `openMenu`).
