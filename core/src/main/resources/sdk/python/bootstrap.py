"""Launch a user script with the bundled SDK on its import path."""
import pathlib
import runpy
import sys

entrypoint = pathlib.Path(sys.argv[1]).resolve()
sys.argv = [str(entrypoint)]
sys.path.insert(0, str(entrypoint.parent))
sys.path.insert(0, str(pathlib.Path(__file__).parent))
runpy.run_path(str(entrypoint), run_name="__main__")
