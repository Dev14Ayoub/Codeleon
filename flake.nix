{
  description = "Codeleon development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";

  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            packages = with pkgs; [
              docker-client
              git
              jdk21_headless
              maven
              nodejs_20
              postgresql_16
            ];

            shellHook = ''
              echo "Codeleon dev shell"
              echo "Backend:  mvn -f backend/pom.xml test"
              echo "Frontend: npm --prefix frontend-web run build"
            '';
          };
        });
    };
}
