{
  description = "Development shell flake replacing shell.nix";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/1905cec8cba85fa49b09b7d0d8a9b3e2bc52d97a";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          env.LANG = "C.UTF-8";
          env.LC_ALL = "C.UTF-8";
          packages = [
            pkgs.gcc
            pkgs.cmake
            pkgs.zlib
            pkgs.zstd
            pkgs.libjpeg
            pkgs.libpng
            pkgs.libGL
            pkgs.SDL2
            pkgs.openal
            pkgs.curl
            pkgs.libvorbis
            pkgs.libogg
            pkgs.gettext
            pkgs.freetype
            pkgs.sqlite
          ];
        };
      }
    );
}
