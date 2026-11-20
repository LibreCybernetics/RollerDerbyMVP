{
  description = "Development shell for Roller Derby MVP";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { nixpkgs, ... }: {
    devShells = nixpkgs.lib.genAttrs [
      "x86_64-linux"
      "aarch64-linux"
      "x86_64-darwin"
      "aarch64-darwin"
    ] (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        jdk = pkgs.jdk21;
      in {
        default = pkgs.mkShell {
          packages = [ jdk (pkgs.sbt.override { jre = jdk; }) pkgs.nodejs_24 ];
          JAVA_HOME = jdk.home;
        };
      });
  };
}
