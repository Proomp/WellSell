package com.wellsetups.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Checks direct Bukkit bytecode references without loading or booting a Minecraft server. */
public final class ApiCompatibility {
  private record ApiClass(
      boolean isInterface,
      String parent,
      List<String> interfaces,
      Set<String> methods,
      Set<String> fields) {}

  private ApiCompatibility() {}

  public static void main(String[] arguments) throws IOException {
    Map<String, ApiClass> apiTypes = readApi(Path.of(arguments[1]));
    List<String> failures = new ArrayList<>();
    int checked = 0;
    try (var paths = Files.walk(Path.of(arguments[0]))) {
      for (Path file : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
        checked++;
        new ClassReader(Files.readAllBytes(file))
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access,
                      String name,
                      String descriptor,
                      String signature,
                      String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                      @Override
                      public void visitMethodInsn(
                          int opcode,
                          String owner,
                          String method,
                          String type,
                          boolean isInterface) {
                        if (!owner.startsWith("org/bukkit/")) {
                          return;
                        }
                        ApiClass target = apiTypes.get(owner);
                        if (target == null) {
                          failures.add(file.getFileName() + ": missing API class " + owner);
                        } else if (target.isInterface() != isInterface) {
                          failures.add(
                              file.getFileName()
                                  + ": class/interface invocation changed for "
                                  + owner
                                  + "."
                                  + method);
                        } else if (!hasMember(
                            apiTypes, owner, method + type, true, new HashSet<>())) {
                          failures.add(
                              file.getFileName()
                                  + ": missing API method "
                                  + owner
                                  + "."
                                  + method
                                  + type);
                        }
                      }

                      @Override
                      public void visitFieldInsn(
                          int opcode, String owner, String field, String type) {
                        if (owner.startsWith("org/bukkit/")
                            && !hasMember(apiTypes, owner, field + type, false, new HashSet<>())) {
                          failures.add(
                              file.getFileName() + ": missing API field " + owner + "." + field);
                        }
                      }
                    };
                  }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
    }
    if (!failures.isEmpty()) {
      throw new IllegalStateException(String.join(System.lineSeparator(), failures));
    }
    System.out.println(
        "Verified direct Bukkit references in "
            + checked
            + " classes against "
            + Path.of(arguments[1]).getFileName());
    System.out.println(
        "This checks binary references, not server behavior, reflection targets or integrations.");
  }

  private static boolean hasMember(
      Map<String, ApiClass> api,
      String owner,
      String signature,
      boolean method,
      Set<String> visited) {
    if (!visited.add(owner)) {
      return false;
    }
    ApiClass type = api.get(owner);
    if (type == null) {
      // Java base methods (e.g. Enum.name) remain the JDK's compatibility responsibility.
      return owner.startsWith("java/") && jdkMember(owner, signature, method);
    }
    if ((method ? type.methods() : type.fields()).contains(signature)) {
      return true;
    }
    for (String parent : type.interfaces()) {
      if (hasMember(api, parent, signature, method, visited)) {
        return true;
      }
    }
    // Do not let Object hide a missing Bukkit member: only known Object/Enum methods
    // may terminate resolution outside the supplied API jar.
    if (type.parent() == null) {
      return false;
    }
    if (type.parent().startsWith("java/")) {
      return jdkMember(type.parent(), signature, method);
    }
    return hasMember(api, type.parent(), signature, method, visited);
  }

  private static boolean jdkMember(String owner, String signature, boolean method) {
    try {
      Class<?> type =
          Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
      if (method) {
        for (var candidate : type.getMethods()) {
          if ((candidate.getName() + Type.getMethodDescriptor(candidate)).equals(signature)) {
            return true;
          }
        }
      } else {
        for (var candidate : type.getFields()) {
          if ((candidate.getName() + Type.getDescriptor(candidate.getType())).equals(signature)) {
            return true;
          }
        }
      }
      return false;
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException("JDK type unavailable: " + owner, failure);
    }
  }

  private static Map<String, ApiClass> readApi(Path jar) throws IOException {
    Map<String, ApiClass> result = new HashMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.getName().startsWith("org/bukkit/") || !entry.getName().endsWith(".class")) {
          continue;
        }
        try (InputStream input = zip.getInputStream(entry)) {
          new ClassReader(input)
              .accept(
                  new ClassVisitor(Opcodes.ASM9) {
                    private String name;
                    private String parent;
                    private List<String> interfaces;
                    private boolean isInterface;
                    private final Set<String> methods = new HashSet<>();
                    private final Set<String> fields = new HashSet<>();

                    @Override
                    public void visit(
                        int version,
                        int access,
                        String className,
                        String signature,
                        String superName,
                        String[] implemented) {
                      name = className;
                      parent = superName;
                      interfaces = List.of(implemented);
                      isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                      methods.add(name + descriptor);
                      return null;
                    }

                    @Override
                    public FieldVisitor visitField(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        Object value) {
                      fields.add(name + descriptor);
                      return null;
                    }

                    @Override
                    public void visitEnd() {
                      result.put(
                          name, new ApiClass(isInterface, parent, interfaces, methods, fields));
                    }
                  },
                  ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
      }
    }
    return result;
  }
}
