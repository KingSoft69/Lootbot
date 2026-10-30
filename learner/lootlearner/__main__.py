"""python -m lootlearner <command>

  serve                          run the learner (HTTP API and the background loop)
  tokens                         print the bot and operator tokens
  targets add <x> <z> [--y Y] [--dim D] [--label L] [--source S]
  targets import <file.csv> [--source S]
  targets gridfly <gridfly.db> [--source S]   stashes from a copy of the GridFly server's database
  targets list [--status S]
  report [--publish]             write data/reports/latest.md (and push it when --publish)
  gridfly sync                   take targets from the GridFly server's finds now (and send looted marks)
"""

import argparse
import json
import sys

from . import targets
from .server import Learner, load_config, serve


def main(argv=None):
    p = argparse.ArgumentParser(prog="lootlearner")
    p.add_argument("--data", default="data", help="data folder (config, database, jars, reports)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("serve")
    sub.add_parser("tokens")
    t = sub.add_parser("targets")
    tsub = t.add_subparsers(dest="tcmd", required=True)
    a = tsub.add_parser("add")
    a.add_argument("x", type=float)
    a.add_argument("z", type=float)
    a.add_argument("--y", type=int)
    a.add_argument("--dim", default="overworld")
    a.add_argument("--label")
    a.add_argument("--source", default="manual")
    i = tsub.add_parser("import")
    i.add_argument("file")
    i.add_argument("--source", default="import")
    g = tsub.add_parser("gridfly")
    g.add_argument("file")
    g.add_argument("--source", default="gridfly")
    ls = tsub.add_parser("list")
    ls.add_argument("--status")
    gf = sub.add_parser("gridfly")
    gf.add_argument("gcmd", choices=["sync"])
    r = sub.add_parser("report")
    r.add_argument("--publish", action="store_true")
    args = p.parse_args(argv)

    if args.cmd == "serve":
        serve(args.data)
        return 0
    if args.cmd == "tokens":
        cfg = load_config(args.data)
        print(f"bot token:      {cfg['botToken']}\noperator token: {cfg['operatorToken']}")
        return 0
    learner = Learner(args.data)
    if args.cmd == "targets":
        if args.tcmd == "add":
            print(f"target {targets.add(learner.db, args.x, args.z, args.y, args.dim, args.label, args.source)}")
        elif args.tcmd == "import":
            print(f"{targets.import_csv(learner.db, args.file, args.source)} target(s) read")
        elif args.tcmd == "gridfly":
            print(f"{targets.import_gridfly(learner.db, args.file, args.source)} stash(es) read")
        else:
            sql, q = "SELECT id, x, z, dim, status, attempts, last_outcome, label FROM targets", ()
            if args.status:
                sql, q = sql + " WHERE status = ?", (args.status,)
            for row in learner.db.all(sql + " ORDER BY id", q):
                print(json.dumps(row))
        return 0
    if args.cmd == "gridfly":
        if not learner.config.get("gridfly", {}).get("url"):
            print("Set gridfly.url (ws://<gridfly server>:25600/ui) and gridfly.token (its operator token) in config.json first")
            return 1
        learner.gridfly.run()
        return 0
    if args.cmd == "report":
        sys.stdout.write(learner.write_report(publish=args.publish))
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
